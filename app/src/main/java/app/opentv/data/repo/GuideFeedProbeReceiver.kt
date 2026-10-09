package app.opentv.data.repo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.opentv.core.ServiceLocator
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Additional transport-stream budget (after any OkHttp content decoding), before gzip sniffing. */
internal class GuideFeedTransportLimit(input: InputStream, private val limit: Long = 64L * 1024 * 1024) : FilterInputStream(input) {
    var bytes = 0L; private set
    var limitReached = false; private set
    private fun over(): Nothing { limitReached = true; throw IOException("Diagnostic transport budget") }
    override fun read(): Int {
        val value = `in`.read()
        if (value >= 0) { if (bytes >= limit) over(); bytes++ }
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytes >= limit) return read().let { if (it < 0) -1 else error("Unreachable") }
        val n = `in`.read(buffer, offset, minOf(length.toLong(), limit - bytes).toInt())
        if (n > 0) bytes += n
        return n
    }
}

@Serializable internal data class GuideFeedProbeEvidence(val feedId: Long, val lastBulkSuccessMillis: Long,
    val capturedMillis: Long, val transportBytes: Long, val transportLimitReached: Boolean,
    val report: GuideFeedDiagnostic.Report)

/** Explicit DUMP-protected START/POLL/CANCEL; no foreground broadcast waits, writes or auto-retries. */
class GuideFeedProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val reply = session.handle(intent.getStringExtra("operation") ?: "poll", intent.getStringExtra("token"),
            intent.getLongExtra("feed", 0), intent.getIntExtra("offset", 0), intent.getIntExtra("limit", 100)) { feed, now ->
            ServiceLocator.get(app).epgRepository.probeGuideFeed(feed, now)
        }
        resultData = json.encodeToString(reply); resultCode = 0
    }
    companion object {
        const val ACTION = "app.opentv.GUIDE_FEED_PROBE"
        private val json = Json { encodeDefaults = true }
        private val session = GuideFeedProbeSession(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}

@Serializable internal data class GuideFeedProbeReply(val token: String = "", val status: String,
    val total: Int = 0, val offset: Int = 0, val evidence: GuideFeedProbeEvidence? = null)

internal class GuideFeedProbeSession(private val scope: CoroutineScope,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var state = GuideFeedProbeReply(status = "NOT_STARTED")
    private var evidence: GuideFeedProbeEvidence? = null
    private var job: Job? = null
    private var expiry: Job? = null
    private var changed = 0L
    fun handle(op: String, requested: String?, feed: Long = 0, offsetRequested: Int = 0, limit: Int = 100,
        probe: suspend (Long, Long) -> GuideFeedProbeEvidence): GuideFeedProbeReply = synchronized(lock) {
            when {
                op == "start" -> {
                    if (feed <= 0) GuideFeedProbeReply(status = "INVALID_REQUEST")
                    else if (state.status == "RUNNING") GuideFeedProbeReply(status = "BUSY")
                    else {
                        expiry?.cancel()
                        evidence = null
                        state = GuideFeedProbeReply(UUID.randomUUID().toString(), "RUNNING")
                        val token = state.token
                        val captured = wall()
                        job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val result = withTimeout(55_000) { probe(feed, captured) }
                                synchronized(lock) {
                                    if (state.token == token && state.status == "RUNNING") {
                                        evidence = result; state = state.copy(status = "FINISHED")
                                        changed = elapsed()
                                        scheduleExpiry(token)
                                    }
                                }
                            } catch (_: CancellationException) { finish(token, "CANCELLED_OR_TIMED_OUT") }
                            catch (_: Exception) { finish(token, "UNAVAILABLE") }
                        }
                        job!!.start(); state
                    }
                }
                requested == null || requested != state.token -> GuideFeedProbeReply(status = "UNKNOWN_TOKEN")
                state.status != "RUNNING" && elapsed() - changed > 300_000 -> {
                    evidence = null; state = state.copy(status = "EXPIRED"); state
                }
                op == "cancel" -> {
                    if (state.status == "RUNNING") {
                        job?.cancel(); evidence = null; state = state.copy(status = "CANCELLED")
                        changed = elapsed(); scheduleExpiry(state.token)
                    }
                    state
                }
                op == "poll" -> {
                    val full = evidence
                    if (full == null) state else {
                        val total = full.report.rows.size
                        val offset = offsetRequested.coerceIn(0, total)
                        val end = (offset + limit.coerceIn(1, 100)).coerceAtMost(total)
                        state.copy(total = total, offset = offset,
                            evidence = full.copy(report = full.report.copy(rows = full.report.rows.subList(offset, end))))
                    }
                }
                else -> GuideFeedProbeReply(status = "INVALID_REQUEST")
            }
        }
        private fun finish(token: String, status: String) = synchronized(lock) {
            if (state.token == token && state.status == "RUNNING") {
                state = state.copy(status = status); changed = elapsed()
                scheduleExpiry(token)
            }
        }
        private fun scheduleExpiry(token: String) {
            expiry?.cancel()
            expiry = scope.launch {
                delay(300_000)
                synchronized(lock) {
                    if (state.token == token && state.status != "RUNNING") {
                        evidence = null; state = state.copy(status = "EXPIRED")
                    }
                }
            }
        }
}
