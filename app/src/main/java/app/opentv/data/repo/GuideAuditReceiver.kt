package app.opentv.data.repo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.opentv.core.ServiceLocator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Read-only, ADB/system DUMP-protected diagnostics; not an HTTP or public-app API. */
class GuideAuditReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        scope.launch {
            try {
                val result = withTimeout(8_000) { mutex.withLock {
                    val requested = intent.getStringExtra("snapshot")
                    if (requested == null) {
                        rows = ServiceLocator.get(context).epgRepository.auditChannels(System.currentTimeMillis())
                        token = UUID.randomUUID().toString()
                        created = SystemClock.elapsedRealtime()
                    } else if (requested != token || SystemClock.elapsedRealtime() - created > 120_000) {
                        return@withLock "{\"error\":\"Snapshot expired; start again\"}"
                    }
                    Json.encodeToString(Reply(token, GuideCoverageAudit.page(rows,
                        intent.getIntExtra("offset", 0), intent.getIntExtra("limit", 100))))
                } }
                pending.setResultData(result)
                pending.setResultCode(0)
            } catch (cancelled: CancellationException) {
                pending.setResultData("{\"error\":\"Audit canceled or timed out\"}")
                pending.setResultCode(1)
                throw cancelled
            } catch (_: Exception) {
                pending.setResultData("{\"error\":\"Audit unavailable\"}")
                pending.setResultCode(1)
            } finally { pending.finish() }
        }
    }

    @Serializable private data class Reply(val snapshot: String, val page: GuideCoveragePage)
    companion object {
        const val ACTION = "app.opentv.GUIDE_AUDIT"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var token = ""
        private var created = 0L
        private var rows: List<GuideChannelAudit> = emptyList()
    }
}
