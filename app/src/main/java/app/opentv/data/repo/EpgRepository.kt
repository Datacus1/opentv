/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.content.Context
import android.os.StatFs
import android.util.Log
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpgChannelAliasDao
import app.opentv.data.db.EpgFeedDao
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.ProgrammeDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the electronic programme guide, with a cache policy sized for small Android TV storage.
 *
 * The working cache always remains readable while a replacement streams in. A failed download
 * never clears it. Successful refreshes update programmes by their natural XMLTV identity
 * (feed/channel/start), then reconcile stale rows in bounded transactions. Every ingestion and
 * deletion path observes a protected free-space reserve; no single SQL statement is allowed to
 * delete an unbounded number of rows.
 */
class EpgRepository(
    private val context: Context,
    private val database: OpenTvDatabase,
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    /** False while live playback or recording makes storage maintenance undesirable. */
    private val maintenanceAllowed: suspend () -> Boolean = { true },
) {

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    data class StorageDiagnostics(
        val freeBytes: Long,
        val databaseBytes: Long,
        val walBytes: Long,
        val programmeRows: Int,
    )

    private data class FeedCoverage(val populatedChannels: Int, val coveredChannels: Int)

    private sealed interface FeedResult {
        data class Success(
            val programmes: Int,
            val channels: Int,
            val truncated: Boolean,
        ) : FeedResult

        data class Failed(val reason: String) : FeedResult
    }

    private class StorageReserveException(message: String) : IllegalStateException(message)

    /** One repository instance owns every guide mutation, so sync and deletion cannot interleave. */
    private val mutationMutex = Mutex()
    private val databaseFile: File = context.getDatabasePath(DATABASE_NAME)
    private val walFile: File = File(databaseFile.path + "-wal")

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

    suspend fun upcoming(epgChannelId: String, nowUtcMillis: Long, limit: Int = 12): List<Programme> =
        programmeDao.upcoming(epgChannelId, nowUtcMillis, limit)

    suspend fun storageDiagnostics(): StorageDiagnostics = withContext(Dispatchers.IO) {
        StorageDiagnostics(
            freeBytes = availableBytes(),
            databaseBytes = databaseFile.length(),
            walBytes = walFile.length(),
            programmeRows = programmeDao.countAll(),
        )
    }

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val trimmed = url.trim().take(MAX_URL_CHARS)
            if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return@withLock
            feedDao.insert(
                EpgFeed(
                    name = name.trim().ifBlank { trimmed }.take(MAX_TITLE_CHARS),
                    url = trimmed,
                    enabled = true,
                ),
            )
        }
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutationMutex.withLock { feedDao.setEnabled(id, enabled) }
    }

    /** Persist the tombstone first; bounded cleanup can safely resume after cancellation/reboot. */
    suspend fun removeFeed(feed: EpgFeed) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            feedDao.markDeleting(feed.id)
            cleanupDeletedFeedLocked(feed.id)
        }
    }

    suspend fun removeProviderFeed(sourceId: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            feedDao.forProvider(sourceId)?.let { feed ->
                feedDao.markDeleting(feed.id)
                cleanupDeletedFeedLocked(feed.id)
            }
        }
    }

    suspend fun ensureFeeds() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            resumePendingDeletionsLocked()
            ensureFeedsLocked()
        }
    }

    private suspend fun ensureFeedsLocked() {
        val existing = feedDao.all()
        for (source in sourceDao.enabled()) {
            if (existing.none { it.providerSourceId == source.id }) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }

        // Publishers rename country files occasionally. Stable-name migration preserves the cache
        // and user toggle; the next complete successful refresh reconciles old rows and aliases.
        val builtInsByName = feedDao.all().filter { it.builtIn }.associateBy { it.name }
        for ((name, url) in BUILT_IN_FEEDS) {
            val saved = builtInsByName[name]
            if (saved != null) {
                if (!saved.deleting && saved.url != url) {
                    feedDao.update(
                        saved.copy(
                            url = url,
                            lastSyncMillis = 0L,
                            lastAttemptMillis = 0L,
                            failureCount = 0,
                            lastResult = "",
                        ),
                    )
                }
            } else if (feedDao.byUrl(url) == null) {
                // The compact team-sports guide complements the already-selected US lineup.
                // Inherit that choice only when the feed is first introduced. If the user later
                // disables sports, this branch no longer runs and their preference is preserved.
                val enabled = defaultEnabledForNewBuiltIn(
                    name = name,
                    usaMainEnabled = builtInsByName[USA_FEED_NAME]?.enabled == true,
                )
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = enabled))
            }
        }
    }

    private suspend fun resumePendingDeletionsLocked() {
        if (!maintenanceAllowed() || availableBytes() < SOFT_FREE_RESERVE_BYTES) return
        for (feed in feedDao.pendingDeletion()) {
            if (!cleanupDeletedFeedLocked(feed.id)) return
        }
    }

    /** Returns true only when all programme and alias rows plus the feed row are gone. */
    private suspend fun cleanupDeletedFeedLocked(feedId: Long): Boolean {
        if (!maintenanceAllowed() || availableBytes() < SOFT_FREE_RESERVE_BYTES) return false
        while (true) {
            if (!maintenanceAllowed() || availableBytes() < HARD_FREE_FLOOR_BYTES) return false
            val removed = programmeDao.deleteBatchForFeed(feedId, DELETE_BATCH_SIZE)
            if (removed == 0) break
            checkpointIfWalLarge()
            yield()
        }
        aliasDao.deleteForFeed(feedId)
        feedDao.delete(feedId)
        return true
    }

    // ---- Sync ------------------------------------------------------------------------------

    suspend fun syncAll(nowUtcMillis: Long, force: Boolean = false): SyncSummary =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                resumePendingDeletionsLocked()
                ensureFeedsLocked()
                maybeAutoEnableRegionalFeedLocked()

                var succeeded = 0
                var failed = 0
                var written = 0

                for (feed in feedDao.enabled()) {
                    val coverage = coverageFor(feed.id, nowUtcMillis)
                    if (
                        !shouldRefreshFeed(
                            lastSyncMillis = feed.lastSyncMillis,
                            lastAttemptMillis = feed.lastAttemptMillis,
                            failureCount = feed.failureCount,
                            populatedChannels = coverage.populatedChannels,
                            coveredChannels = coverage.coveredChannels,
                            nowUtcMillis = nowUtcMillis,
                            force = force,
                        )
                    ) {
                        succeeded++
                        continue
                    }

                    if (availableBytes() < SOFT_FREE_RESERVE_BYTES) {
                        val reason = "Refresh deferred to protect the Shield's storage reserve."
                        feedDao.markFailed(feed.id, nowUtcMillis, reason)
                        Log.w(TAG, "Feed '${feed.name}' deferred: low free space")
                        failed++
                        continue
                    }

                    when (val result = syncFeed(feed, nowUtcMillis)) {
                        is FeedResult.Success -> {
                            succeeded++
                            written += result.programmes
                            val suffix = if (result.truncated) " (storage-safe limit reached)" else ""
                            feedDao.markSucceeded(
                                feed.id,
                                nowUtcMillis,
                                "${result.programmes} programmes, ${result.channels} channels$suffix",
                            )

                            // Only a complete parse proves which old rows disappeared upstream.
                            if (!result.truncated && maintenanceAllowed()) {
                                deleteInBatches {
                                    programmeDao.deleteStaleBatchForFeed(
                                        feed.id,
                                        nowUtcMillis,
                                        DELETE_BATCH_SIZE,
                                    )
                                }
                            }
                        }

                        is FeedResult.Failed -> {
                            failed++
                            feedDao.markFailed(feed.id, nowUtcMillis, result.reason)
                            Log.w(TAG, "Feed '${feed.name}' failed: ${result.reason}")
                        }
                    }
                }

                runBoundedRetentionLocked(nowUtcMillis)
                val (matched, total) = runMatcherLocked()
                truncateWalWhenIdle()
                logStorageState()
                SyncSummary(succeeded, failed, written, matched, total)
            }
        }

    private suspend fun coverageFor(feedId: Long, nowUtcMillis: Long): FeedCoverage {
        val populated = programmeDao.populatedChannelsForFeed(
            feedId,
            nowUtcMillis,
            nowUtcMillis + RETENTION_FUTURE_MILLIS,
        )
        val covered = programmeDao.channelsCovering(
            feedId,
            nowUtcMillis + MIN_CACHED_FUTURE_MILLIS,
        )
        return FeedCoverage(populated, covered)
    }

    private suspend fun syncFeed(feed: EpgFeed, nowUtcMillis: Long): FeedResult {
        val batch = ArrayList<Programme>(BATCH_SIZE)
        val aliases = LinkedHashMap<String, EpgChannelAlias>(512)
        var written = 0
        var accepted = 0
        var truncated = false
        val oldestAllowed = nowUtcMillis - RETENTION_PAST_MILLIS
        val newestAllowed = nowUtcMillis + RETENTION_FUTURE_MILLIS

        return try {
            openFeedStream(feed).use { stream ->
                val stats = XmltvParser.parse(
                    input = stream,
                    feedId = feed.id,
                    onChannelAlias = { rawId, rawDisplayName ->
                        val id = rawId.take(MAX_EPG_ID_CHARS)
                        if (aliases.size >= MAX_ALIASES_PER_FEED && id !in aliases) {
                            truncated = true
                        } else {
                            val displayName = (rawDisplayName ?: id).take(MAX_TITLE_CHARS)
                            aliases[id] = EpgChannelAlias(
                                feedId = feed.id,
                                epgId = id,
                                displayName = displayName,
                                normalizedKey = ChannelNameNormalizer.normalize(displayName).groupKey,
                            )
                        }
                    },
                    onProgramme = { raw ->
                        if (raw.endUtcMillis < oldestAllowed || raw.startUtcMillis > newestAllowed) {
                            return@parse
                        }
                        if (accepted >= MAX_PROGRAMMES_PER_FEED) {
                            truncated = true
                            return@parse
                        }
                        batch += sanitizeProgramme(raw, nowUtcMillis)
                        accepted++
                        if (batch.size >= BATCH_SIZE) {
                            // StatFs is intentionally checked once per transaction rather than
                            // once per programme; a large XMLTV file can contain hundreds of
                            // thousands of entries and filesystem calls in the parser hot path
                            // noticeably delay the guide becoming usable.
                            ensureHardWriteReserve()
                            programmeDao.upsertAll(batch)
                            written += batch.size
                            batch.clear()
                            checkpointIfWalLarge()
                            yield()
                        }
                    },
                )

                if (batch.isNotEmpty()) {
                    ensureHardWriteReserve()
                    programmeDao.upsertAll(batch)
                    written += batch.size
                    batch.clear()
                    checkpointIfWalLarge()
                }

                if (written == 0) {
                    return FeedResult.Failed(
                        if (stats.programmeCount == 0) {
                            "Downloaded, but contained no programmes."
                        } else {
                            "Downloaded, but contained no programmes in the usable guide window."
                        },
                    )
                }

                // Aliases are small enough to replace atomically, and only after the XML completed.
                if (aliases.isNotEmpty()) aliasDao.replaceForFeed(feed.id, aliases.values.toList())
                FeedResult.Success(written, stats.channelCount, truncated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageReserveException) {
            FeedResult.Failed(e.message ?: "Refresh stopped to protect free storage.")
        } catch (e: Exception) {
            // No cleanup here: the last complete cache is still present. A later complete refresh
            // reconciles any partial newer rows through lastSeenSyncMillis.
            FeedResult.Failed(e.message ?: "Download failed.")
        }
    }

    private fun sanitizeProgramme(programme: Programme, syncMillis: Long): Programme =
        programme.copy(
            epgChannelId = programme.epgChannelId.take(MAX_EPG_ID_CHARS),
            title = programme.title.take(MAX_TITLE_CHARS),
            description = programme.description?.take(MAX_DESCRIPTION_CHARS),
            category = programme.category?.take(MAX_CATEGORY_CHARS),
            iconUrl = programme.iconUrl?.take(MAX_URL_CHARS),
            lastSeenSyncMillis = syncMillis,
        )

    private suspend fun runBoundedRetentionLocked(nowUtcMillis: Long) {
        if (!maintenanceAllowed() || availableBytes() < SOFT_FREE_RESERVE_BYTES) return
        deleteInBatches {
            programmeDao.deleteExpiredBatch(
                nowUtcMillis - RETENTION_PAST_MILLIS,
                DELETE_BATCH_SIZE,
            )
        }
        deleteInBatches {
            programmeDao.deleteTooFarFutureBatch(
                nowUtcMillis + RETENTION_FUTURE_MILLIS,
                DELETE_BATCH_SIZE,
            )
        }
        for (feed in feedDao.all().filterNot { it.deleting }) {
            deleteFeedOverflowLocked(feed.id)
        }
        deleteGlobalOverflowLocked()
    }

    private suspend fun deleteFeedOverflowLocked(feedId: Long) {
        while (maintenanceAllowed() && availableBytes() >= HARD_FREE_FLOOR_BYTES) {
            val excess = programmeDao.countForFeed(feedId) - MAX_PROGRAMMES_PER_FEED
            if (excess <= 0) return
            programmeDao.deleteFarthestFutureBatchForFeed(feedId, minOf(excess, DELETE_BATCH_SIZE))
            checkpointIfWalLarge()
            yield()
        }
    }

    private suspend fun deleteGlobalOverflowLocked() {
        while (maintenanceAllowed() && availableBytes() >= HARD_FREE_FLOOR_BYTES) {
            val excess = programmeDao.countAll() - MAX_PROGRAMMES_TOTAL
            if (excess <= 0) return
            programmeDao.deleteFarthestFutureBatch(minOf(excess, DELETE_BATCH_SIZE))
            checkpointIfWalLarge()
            yield()
        }
    }

    private suspend fun deleteInBatches(delete: suspend () -> Int) {
        while (maintenanceAllowed() && availableBytes() >= HARD_FREE_FLOOR_BYTES) {
            val removed = delete()
            if (removed == 0) return
            checkpointIfWalLarge()
            yield()
        }
    }

    private suspend fun ensureHardWriteReserve() {
        if (availableBytes() < HARD_FREE_FLOOR_BYTES) {
            throw StorageReserveException("Refresh stopped to preserve 1.10 GiB of free storage.")
        }
    }

    private fun availableBytes(): Long = runCatching {
        StatFs(context.dataDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private fun checkpointIfWalLarge() {
        if (walFile.length() < WAL_CHECKPOINT_BYTES) return
        runCatching { checkpoint("PASSIVE") }
            .onFailure { Log.w(TAG, "Passive WAL checkpoint failed", it) }
    }

    private suspend fun truncateWalWhenIdle() {
        if (
            walFile.length() < WAL_CHECKPOINT_BYTES ||
            !maintenanceAllowed() ||
            availableBytes() < SOFT_FREE_RESERVE_BYTES
        ) return
        runCatching { checkpoint("TRUNCATE") }
            .onFailure { Log.w(TAG, "Idle WAL truncation failed", it) }
    }

    private fun checkpoint(mode: String) {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint($mode)")
            .use { cursor -> while (cursor.moveToNext()) Unit }
    }

    private suspend fun logStorageState() {
        Log.i(
            TAG,
            "Storage: free=${availableBytes()} db=${databaseFile.length()} wal=${walFile.length()} " +
                "programmes=${programmeDao.countAll()}",
        )
    }

    private suspend fun openFeedStream(feed: EpgFeed): InputStream {
        val raw: InputStream = when {
            feed.providerSourceId != null -> {
                val source: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                api.openEpgStream(source)
            }

            feed.url != null -> {
                val response = http.newCall(Request.Builder().url(feed.url).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("Guide download failed (HTTP ${response.code}).")
                }
                response.body?.byteStream()
                    ?: throw IllegalStateException("The server returned an empty guide.")
            }

            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }

        val buffered = BufferedInputStream(raw, 8 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    // ---- Matching --------------------------------------------------------------------------

    suspend fun runMatcher(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        mutationMutex.withLock { runMatcherLocked() }
    }

    private suspend fun runMatcherLocked(): Pair<Int, Int> {
        val aliases = aliasDao.allFromEnabledFeeds()
        val populated = programmeDao.channelIdsWithProgrammesFromEnabledFeeds().toHashSet()
        // Empty aliases must not make a populated alias look ambiguous. The UI can only use a
        // match backed by programmes, so build the automatic index from that same usable set.
        val populatedAliases = aliases.filter { it.epgId in populated }
        val index = EpgMatcher.buildIndex(populatedAliases.map { it.epgId to it.displayName })
        val channels = channelDao.allForMatching()
        var matched = 0

        for (channel in channels) {
            // Recompute from the preserved provider name so matcher improvements take effect on
            // an in-place upgrade even before the next catalogue refresh rewrites stored keys.
            val currentGroupKey = ChannelNameNormalizer.normalize(channel.name).groupKey
            val newMatch = index.matchProvider(currentGroupKey, channel.epgChannelId)
            if (newMatch != channel.matchedEpgId) channelDao.setMatchedEpgId(channel.id, newMatch)
            if (
                channel.epgCandidates.any { it in populated } ||
                    (newMatch != null && newMatch in populated)
            ) matched++
        }

        Log.i(
            TAG,
            "Matcher: $matched of ${channels.size} channels have a working guide " +
                "(${aliases.size} enabled guide aliases, ${populated.size} populated channels)",
        )
        return matched to channels.size
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    private suspend fun maybeAutoEnableRegionalFeedLocked() {
        val all = feedDao.all().filterNot { it.deleting }
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return
        val pristine = builtIns.all { !it.enabled && it.lastSyncMillis == 0L } &&
            all.none { !it.builtIn && it.providerSourceId == null }
        if (!pristine) return

        val regionCounts = HashMap<String, Int>()
        for (channel in channelDao.allForMatching()) {
            val region = ChannelNameNormalizer.normalize(channel.name).region ?: continue
            regionCounts.merge(region, 1, Int::plus)
        }
        val top = regionCounts.maxByOrNull { it.value } ?: return
        if (top.value < MIN_CHANNELS_FOR_AUTO_REGION) return

        val feedName = REGION_TO_FEED[top.key] ?: return
        val feed = builtIns.firstOrNull { it.name == feedName } ?: return
        feedDao.setEnabled(feed.id, true)
        Log.i(TAG, "Auto-enabled '${feed.name}' — ${top.value} channels tagged ${top.key}")
    }

    companion object {
        private const val TAG = "EpgRepository"
        private const val DATABASE_NAME = "opentv.db"

        const val BATCH_SIZE = 500
        const val DELETE_BATCH_SIZE = 1_000
        const val MAX_PROGRAMMES_PER_FEED = 150_000
        const val MAX_PROGRAMMES_TOTAL = 200_000
        const val MAX_ALIASES_PER_FEED = 20_000

        const val MAX_EPG_ID_CHARS = 512
        const val MAX_TITLE_CHARS = 512
        const val MAX_DESCRIPTION_CHARS = 2_048
        const val MAX_CATEGORY_CHARS = 128
        const val MAX_URL_CHARS = 2_048

        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)
        val RETENTION_FUTURE_MILLIS: Long = TimeUnit.DAYS.toMillis(8)
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
        val MIN_CACHED_FUTURE_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
        val LOW_COVERAGE_RETRY_MILLIS: Long = TimeUnit.HOURS.toMillis(1)

        /** Measured for this Shield's 5.06 GiB writable partition. */
        const val SOFT_FREE_RESERVE_BYTES: Long = 1_342_177_280L // 1.25 GiB
        const val HARD_FREE_FLOOR_BYTES: Long = 1_181_116_006L // 1.10 GiB
        const val WAL_CHECKPOINT_BYTES: Long = 67_108_864L // 64 MiB
        const val MIN_COVERAGE_PERCENT = 70

        val FAILURE_BACKOFFS_MILLIS: LongArray = longArrayOf(
            TimeUnit.MINUTES.toMillis(15),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(6),
            TimeUnit.HOURS.toMillis(24),
        )

        internal fun retryBackoffMillis(failureCount: Int): Long =
            if (failureCount <= 0) 0L
            else FAILURE_BACKOFFS_MILLIS[(failureCount - 1).coerceAtMost(FAILURE_BACKOFFS_MILLIS.lastIndex)]

        internal fun shouldRefreshFeed(
            lastSyncMillis: Long,
            lastAttemptMillis: Long,
            failureCount: Int,
            populatedChannels: Int,
            coveredChannels: Int,
            nowUtcMillis: Long,
            force: Boolean,
        ): Boolean {
            if (force) return true
            val attemptAge = (nowUtcMillis - lastAttemptMillis).coerceAtLeast(0L)
            if (failureCount > 0 && attemptAge < retryBackoffMillis(failureCount)) return false
            if (lastSyncMillis <= 0L) return true
            val successAge = (nowUtcMillis - lastSyncMillis).coerceAtLeast(0L)
            if (successAge >= REFRESH_INTERVAL_MILLIS) return true
            if (attemptAge < LOW_COVERAGE_RETRY_MILLIS) return false
            if (populatedChannels <= 0) return true
            return coveredChannels * 100L < populatedChannels * MIN_COVERAGE_PERCENT.toLong()
        }

        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        const val USA_FEED_NAME = "USA — epgshare01"
        const val USA_SPORTS_FEED_NAME = "USA team sports — epgshare01"

        internal fun defaultEnabledForNewBuiltIn(
            name: String,
            usaMainEnabled: Boolean,
        ): Boolean = name == USA_SPORTS_FEED_NAME && usaMainEnabled

        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to USA_FEED_NAME,
            "USA" to USA_FEED_NAME,
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — Freeview (free-to-air)" to
                "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
            "UK — epgshare01 (Sky lineup)" to
                "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            USA_FEED_NAME to
                "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            USA_SPORTS_FEED_NAME to
                "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
            "Canada — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz",
            "Australia — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
        )
    }
}
