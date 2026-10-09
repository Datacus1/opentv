package app.opentv.data.repo

import app.opentv.data.parser.XmltvParser
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

/** Explicit supplied-stream dry run. No HTTP, database, filesystem, scheduler or app entry point. */
internal object GuideFeedDiagnostic {
    @Serializable enum class Completion { COMPLETE, BYTE_LIMIT, IDENTITY_LIMIT, PROGRAMME_LIMIT, TIME_LIMIT, PARSE_ERROR, CANCELLED }
    data class Limits(val bytes: Long = 128L * 1024 * 1024, val identities: Int = 40_000,
        val programmes: Int = 1_000_000, val millis: Long = 30_000,
        val selectedRows: Int = 200_000 / 3, val selectorChannels: Int = 20_000) {
        init {
            require(bytes in 1..(256L * 1024 * 1024) && identities in 1..40_000)
            require(programmes in 1..1_000_000 && millis in 1..60_000)
            require(selectedRows in 1..200_000 && selectorChannels in 1..20_000)
        }
    }
    @Serializable data class Row(val guideId: String, val fingerprint: String,
        val productionFingerprint: String, val lossyLabel: Boolean, val truncatedProductionId: Boolean,
        val aliasDeclared: Boolean, val encountered: Int, val accepted: Int,
        val blankChannel: Int, val missingStart: Int, val invalidStart: Int, val nonpositiveSpan: Int,
        val defaultedStop: Int, val missingStop: Int, val invalidStop: Int,
        val current: Int, val future: Int, val past: Int,
        val outOfWindow: Int, val offeredToSelector: Int, val selectedProductionKeys: Int,
        val productionIdentityCollision: Boolean)
    @Serializable data class Report(val feedId: Long, val nowMillis: Long, val completion: Completion,
        val bytesRead: Long, val encountered: Int, val selectorTruncated: Boolean,
        val rows: List<Row>,
        val evidence: String = "One supplied stream; no writes or two-pass consistency proof. Partial scans cannot establish absence.")
    private data class Counts(val id: String, val fingerprint: String, val productionFingerprint: String,
        val lossy: Boolean, val truncated: Boolean, var alias: Boolean = false,
        var encountered: Int = 0, var accepted: Int = 0, var blank: Int = 0, var missing: Int = 0,
        var invalid: Int = 0, var span: Int = 0, var defaulted: Int = 0, var missingStop: Int = 0, var invalidStop: Int = 0,
        var current: Int = 0, var future: Int = 0, var past: Int = 0,
        var outside: Int = 0, var offered: Int = 0)
    private class Budget(val completion: Completion) : RuntimeException()

    fun fingerprint(id: String): String = buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))) {
            val value = byte.toInt() and 255
            append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15])
        }
    }

    suspend fun scan(input: InputStream, feedId: Long, now: Long, limits: Limits = Limits(),
        requestedIds: List<String> = emptyList(), clockNanos: () -> Long = System::nanoTime,
        onCancelled: (Report) -> Unit = {},
    ): Report {
        val context = coroutineContext
        val started = clockNanos()
        var bytes = 0L
        var encountered = 0
        var readFailure: Exception? = null
        val counts = LinkedHashMap<String, Counts>()
        val oldest = now - EpgRepository.RETENTION_PAST_MILLIS
        val newest = now + EpgRepository.RETENTION_FUTURE_MILLIS
        val selector = GuideProgrammeSelector(now, oldest, newest, limits.selectedRows, limits.selectorChannels)
        fun check() {
            context.ensureActive()
            if ((clockNanos() - started) / 1_000_000 >= limits.millis) throw Budget(Completion.TIME_LIMIT)
        }
        fun row(raw: String?): Counts {
            val id = raw.orEmpty()
            val hash = fingerprint(id)
            return counts[hash] ?: run {
                if (counts.size >= limits.identities) throw Budget(Completion.IDENTITY_LIMIT)
                val safe = GuideCoverageAudit.safeLabel(id)
                Counts(safe, hash, fingerprint(id.take(EpgRepository.MAX_EPG_ID_CHARS)), safe != id,
                    id.length > EpgRepository.MAX_EPG_ID_CHARS).also { counts[hash] = it }
            }
        }
        fun report(completion: Completion): Report {
            val selected = selector.selected()
            val selections = selected.groupingBy { fingerprint(it.channel) }.eachCount()
            val collisions = counts.values.groupingBy { it.productionFingerprint }.eachCount()
            return Report(feedId, now, completion, bytes, encountered, selector.truncated(selected),
                counts.values.map { c -> Row(c.id, c.fingerprint, c.productionFingerprint, c.lossy, c.truncated,
                    c.alias, c.encountered, c.accepted, c.blank, c.missing, c.invalid, c.span, c.defaulted,
                    c.missingStop, c.invalidStop,
                    c.current, c.future, c.past, c.outside, c.offered,
                    selections[c.productionFingerprint] ?: 0, (collisions[c.productionFingerprint] ?: 0) > 1) })
        }
        // Do not close the caller's stream. Bound delivered bytes; one look-ahead distinguishes EOF at the limit.
        val bounded = object : FilterInputStream(input) {
            override fun read(): Int {
                try {
                    check()
                    val value = `in`.read()
                    if (value >= 0 && bytes >= limits.bytes) throw Budget(Completion.BYTE_LIMIT)
                    if (value >= 0) bytes++
                    return value
                } catch (failure: Exception) { readFailure = failure; throw failure }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                try {
                    check()
                    if (length == 0) return 0
                    if (bytes >= limits.bytes) return read().let { if (it < 0) -1 else error("Unreachable") }
                    val n = `in`.read(buffer, offset, minOf(length.toLong(), limits.bytes - bytes).toInt())
                    if (n > 0) bytes += n
                    return n
                } catch (failure: Exception) { readFailure = failure; throw failure }
            }
        }
        val completion = try {
            requestedIds.forEach { check(); row(it) }
            XmltvParser.parseWithEvidence(bounded, feedId,
                onChannelAlias = { id, _ -> check(); row(id).alias = true },
                onEvidence = { e ->
                    check()
                    if (encountered >= limits.programmes) throw Budget(Completion.PROGRAMME_LIMIT)
                    encountered++
                    val c = row(e.channelId)
                    c.encountered++
                    if (e.defaultedStop) {
                        c.defaulted++
                        if (e.invalidStop) c.invalidStop++ else c.missingStop++
                    }
                    when (e.rejection) {
                        XmltvParser.Rejection.BLANK_CHANNEL -> c.blank++
                        XmltvParser.Rejection.MISSING_START -> c.missing++
                        XmltvParser.Rejection.INVALID_START -> c.invalid++
                        XmltvParser.Rejection.NONPOSITIVE_SPAN -> c.span++
                        null -> {
                            c.accepted++
                            when { e.start!! > now -> c.future++; e.end!! > now -> c.current++; else -> c.past++ }
                            if (e.end!! <= oldest || e.start!! >= newest) c.outside++ else c.offered++
                        }
                    }
                },
                onProgramme = { p ->
                    check()
                    selector.offer(p.copy(epgChannelId = p.epgChannelId.take(EpgRepository.MAX_EPG_ID_CHARS)))
                })
            readFailure?.let { throw it }
            check()
            Completion.COMPLETE
        } catch (failure: Exception) {
            // XML implementations can wrap exceptions raised by their input stream.
            when (val original = readFailure ?: failure) {
                is CancellationException -> { onCancelled(report(Completion.CANCELLED)); throw original }
                is Budget -> original.completion
                // Error messages may embed feed content or credential-bearing IDs; never export them.
                else -> Completion.PARSE_ERROR
            }
        }
        return report(completion)
    }
}
