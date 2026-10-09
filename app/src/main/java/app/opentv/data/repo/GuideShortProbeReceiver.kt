package app.opentv.data.repo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.opentv.core.ServiceLocator
import app.opentv.data.remote.ShortEpgProbeCounts
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class GuideShortProbeRow(val channelId: Long, val sourceId: Long?, val name: String,
    val providerId: String?, val lastBulkSuccessMillis: Long?, val counts: ShortEpgProbeCounts)

/** DUMP protected, explicit only. Immediate ACK; HTTP never holds a broadcast open. */
class GuideShortProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        resultData = json.encodeToString(session.handle(intent.getStringExtra("operation") ?: "poll",
            intent.getStringExtra("token"), intent.getStringExtra("channels")) { ids, now, onRow ->
            ServiceLocator.get(context.applicationContext).epgRepository.probeShortGuides(ids, now, onRow)
        })
        resultCode = 0
    }
    companion object {
        const val ACTION = "app.opentv.GUIDE_SHORT_PROBE"
        private val json = Json { encodeDefaults = true }
        private val session = GuideShortProbeSession(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}

@Serializable internal data class GuideShortProbeReply(val token: String = "", val status: String,
    val capturedMillis: Long = 0, val rows: List<GuideShortProbeRow> = emptyList(),
    val evidence: String = "COMPLETE means requests finished; inspect each row. Short endpoint only, not bulk absence or broadcast proof.")

/** Bounded process-local session; process death loses the token rather than inventing completeness. */
internal class GuideShortProbeSession(private val scope: CoroutineScope,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var state = GuideShortProbeReply(status = "NOT_STARTED")
    private var changed = 0L
    private var job: Job? = null
    fun handle(operation: String, requested: String?, channels: String?,
        probe: suspend (List<Long>, Long, (GuideShortProbeRow) -> Unit) -> Unit): GuideShortProbeReply =
        synchronized(lock) {
            when {
                operation == "start" -> {
                    val raw = if (channels == null || channels.length > 100) emptyList() else channels.split(',')
                    val ids = raw.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }
                    if (raw.size !in 1..4 || ids.size != raw.size || ids.distinct().size != ids.size)
                        GuideShortProbeReply(status = "INVALID_REQUEST")
                    else if (state.status == "RUNNING") GuideShortProbeReply(status = "BUSY")
                    else {
                        state = GuideShortProbeReply(UUID.randomUUID().toString(), "RUNNING", wall())
                        changed = elapsed()
                        val token = state.token
                        val captured = state.capturedMillis
                        job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                withTimeout(45_000) {
                                    probe(ids, captured) { row ->
                                        synchronized(lock) {
                                            if (state.token == token && state.status == "RUNNING")
                                                state = state.copy(rows = state.rows + row)
                                        }
                                    }
                                }
                                finish(token, "COMPLETE")
                            } catch (_: CancellationException) { finish(token, "CANCELLED_OR_TIMED_OUT") }
                            catch (_: Exception) { finish(token, "UNAVAILABLE") }
                        }
                        job!!.start()
                        state
                    }
                }
                requested == null || requested != state.token -> GuideShortProbeReply(status = "UNKNOWN_TOKEN")
                state.status != "RUNNING" && elapsed() - changed > 300_000 -> {
                    state = GuideShortProbeReply(status = "EXPIRED"); state
                }
                operation == "cancel" -> {
                    job?.cancel(); state = state.copy(status = "CANCELLED"); changed = elapsed()
                    state
                }
                operation == "poll" -> state
                else -> GuideShortProbeReply(status = "INVALID_REQUEST")
            }
        }
        private fun finish(token: String, status: String) = synchronized(lock) {
            if (state.token == token && state.status == "RUNNING") {
                state = state.copy(status = status); changed = elapsed()
            }
        }
}
