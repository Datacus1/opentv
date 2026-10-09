/* Copyright (C) 2026 The OpenTV Contributors. GPL v3.0 or later. */
package app.opentv.data.repo

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Metadata only: no paths, provider names/URLs, titles, descriptions or settings values. */
@Serializable data class GuideStorageEvidence(
    val capturedMillis: Long, val freeBytes: Long?, val databaseBytes: Long, val walBytes: Long,
    val pageSize: Long, val pageCount: Long, val reusablePages: Long,
    val tableRows: Map<String, Long>,
    /** null means Android SQLite lacks dbstat, not zero-sized tables. Includes table indexes. */
    val tableAllocatedBytes: Map<String, Long>?,
    val feeds: List<GuideFeedStorageEvidence>,
    val cache: DirectoryStorageEvidence, val codeCache: DirectoryStorageEvidence,
    val internalFiles: DirectoryStorageEvidence, val externalFiles: DirectoryStorageEvidence,
)

@Serializable data class GuideFeedStorageEvidence(
    val feedId: Long, val enabled: Boolean, val deleting: Boolean,
    val lastSuccessMillis: Long, val lastAttemptMillis: Long, val failureCount: Int,
    val programmes: Long, val expired: Long, val eligibleExpired: Long,
    val current: Long, val beyondHorizon: Long, val lastEndMillis: Long?,
)

@Serializable data class DirectoryStorageEvidence(val bytes: Long, val files: Int, val complete: Boolean)

object GuideStorageInspector {
    private val tables = listOf("sources", "categories", "channels", "epg_feeds", "epg_channels",
        "programmes", "movies", "series", "episodes", "playback_positions", "profiles",
        "recordings", "series_rules", "reminders")

    suspend fun inspect(context: Context, database: OpenTvDatabase, now: Long): GuideStorageEvidence =
        withContext(Dispatchers.IO) {
            val cancellation = currentCoroutineContext()
            val parts = database.withTransaction {
                val db = database.openHelper.readableDatabase
                fun scalar(sql: String): Long = db.query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                val rows = tables.associateWith { table ->
                    cancellation.ensureActive(); scalar("SELECT COUNT(*) FROM $table")
                }
                val allocated = try {
                    db.query("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name").use { cursor ->
                        val result = tables.associateWith { 0L }.toMutableMap()
                        while (cursor.moveToNext()) {
                            cancellation.ensureActive()
                            val objectName = cursor.getString(0)
                            val table = tables.firstOrNull { objectName == it || objectName.startsWith("index_${it}_") }
                            if (table != null) result[table] = result.getValue(table) + cursor.getLong(1)
                        }
                        result.toMap()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: android.database.sqlite.SQLiteException) { null }
                val feeds = db.query("""
                    SELECT f.id, f.enabled, f.deleting, f.lastSyncMillis, f.lastAttemptMillis, f.failureCount,
                      COUNT(p.feedId),
                      COALESCE(SUM(CASE WHEN p.endUtcMillis <= ? THEN 1 ELSE 0 END), 0),
                      COALESCE(SUM(CASE WHEN p.endUtcMillis < ? THEN 1 ELSE 0 END), 0),
                      COALESCE(SUM(CASE WHEN p.startUtcMillis <= ? AND p.endUtcMillis > ? THEN 1 ELSE 0 END), 0),
                      COALESCE(SUM(CASE WHEN p.startUtcMillis > ? THEN 1 ELSE 0 END), 0), MAX(p.endUtcMillis)
                    FROM epg_feeds f LEFT JOIN programmes p ON p.feedId=f.id GROUP BY f.id ORDER BY f.id
                """.trimIndent(), arrayOf(now, now - EpgRepository.RETENTION_PAST_MILLIS, now, now,
                    now + EpgRepository.RETENTION_FUTURE_MILLIS)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            cancellation.ensureActive()
                            add(GuideFeedStorageEvidence(cursor.getLong(0), cursor.getInt(1) != 0,
                                cursor.getInt(2) != 0, cursor.getLong(3), cursor.getLong(4), cursor.getInt(5),
                                cursor.getLong(6), cursor.getLong(7), cursor.getLong(8), cursor.getLong(9),
                                cursor.getLong(10), if (cursor.isNull(11)) null else cursor.getLong(11)))
                        }
                    }
                }
                DbParts(scalar("PRAGMA page_size"), scalar("PRAGMA page_count"),
                    scalar("PRAGMA freelist_count"), rows, allocated, feeds)
            }
            val dbFile = context.getDatabasePath("opentv.db")
            GuideStorageEvidence(now, runCatching { StatFs(context.dataDir.absolutePath).availableBytes }.getOrNull(),
                dbFile.length(), File(dbFile.path + "-wal").length(), parts.pageSize, parts.pageCount,
                parts.reusablePages, parts.rows, parts.allocated, parts.feeds,
                measureDirectory(context.cacheDir) { cancellation.ensureActive() },
                measureDirectory(context.codeCacheDir) { cancellation.ensureActive() },
                measureDirectory(context.filesDir) { cancellation.ensureActive() },
                measureDirectory(context.getExternalFilesDir(null)) { cancellation.ensureActive() })
        }

    private data class DbParts(val pageSize: Long, val pageCount: Long, val reusablePages: Long,
        val rows: Map<String, Long>, val allocated: Map<String, Long>?, val feeds: List<GuideFeedStorageEvidence>)

    /** A partial scan is labelled, and symlinks never escape the explicitly owned root. */
    internal fun measureDirectory(root: File?, maxEntries: Int = 4_000, maxMillis: Long = 500,
        checkCancellation: () -> Unit = {}): DirectoryStorageEvidence {
        if (root == null || !root.exists()) return DirectoryStorageEvidence(0, 0, true)
        val started = SystemClock.elapsedRealtime()
        var bytes = 0L; var files = 0; var entries = 0; var complete = true
        return try {
            val canonical = root.canonicalFile
            val prefix = canonical.path + File.separator
            val seen = HashSet<String>()
            val walk = root.walkTopDown().maxDepth(8).onEnter { dir ->
                checkCancellation()
                val target = dir.canonicalPath
                val owned = target == canonical.path || target.startsWith(prefix)
                if (!owned || !seen.add(target)) { complete = false; false }
                else if (File(canonical, dir.relativeTo(root).path).path != target) { complete = false; false }
                else true
            }.onFail { _, _ -> complete = false }
            for (file in walk) {
                checkCancellation()
                if (++entries > maxEntries || SystemClock.elapsedRealtime() - started > maxMillis) {
                    complete = false; break
                }
                if (file.isFile) {
                    val target = file.canonicalPath
                    if (!target.startsWith(prefix) || File(canonical, file.relativeTo(root).path).path != target ||
                        !seen.add(target)) { complete = false; continue }
                    bytes += file.length(); files++
                } else if (file != root && file.relativeTo(root).path.count { it == File.separatorChar } >= 7) {
                    if (file.list()?.isNotEmpty() != false) complete = false
                }
            }
            DirectoryStorageEvidence(bytes, files, complete)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DirectoryStorageEvidence(bytes, files, false) }
    }
}
