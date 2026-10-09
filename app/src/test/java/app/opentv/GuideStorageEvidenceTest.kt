package app.opentv.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideStorageEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `inspection reports actual counts and ages without exporting content or changing rows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        try {
            val now = 1_800_000_000_000L
            db.epgFeeds().insert(EpgFeed(id = 1, name = "PRIVATE-NAME", url = "https://secret.invalid/PASSWORD",
                lastResult = "PRIVATE-RESULT", lastSyncMillis = now - 1000))
            db.epgFeeds().insert(EpgFeed(id = 2, name = "EMPTY", enabled = false))
            val rows = listOf(
                Programme(1, "PRIVATE-ID", now - 3 * 86_400_000, now - 2 * 86_400_000, "PRIVATE-TITLE"),
                Programme(1, "PRIVATE-ID", now - 1000, now + 1000, "PRIVATE-TITLE"),
                Programme(1, "PRIVATE-ID", now + 3 * 86_400_000, now + 4 * 86_400_000, "PRIVATE-TITLE"))
            db.programmes().upsertAll(rows)
            val before = db.epgFeeds().all()
            val report = GuideStorageInspector.inspect(context, db, now)
            assertEquals(3L, report.tableRows["programmes"])
            val feed = report.feeds.first()
            assertEquals(3L, feed.programmes); assertEquals(1L, feed.current)
            assertEquals(1L, feed.expired); assertEquals(1L, feed.eligibleExpired)
            assertEquals(1L, feed.beyondHorizon)
            assertEquals(0L, report.feeds.last().programmes)
            assertEquals(before, db.epgFeeds().all()); assertEquals(3, db.programmes().countAll())
            val json = Json.encodeToString(report)
            for (secret in listOf("PRIVATE", "PASSWORD", "secret.invalid", context.dataDir.path))
                assertFalse(json.contains(secret))
            assertTrue(report.pageSize > 0); assertTrue(report.pageCount > 0)
        } finally { db.close() }
    }
    @Test fun `directory reports bytes and marks partial bounded scans`() {
        val root = temporary.newFolder()
        File(root, "one").writeBytes(ByteArray(17))
        val sub = File(root, "sub").apply { mkdir() }
        File(sub, "two").writeBytes(ByteArray(23))
        val report = GuideStorageInspector.measureDirectory(root)
        assertEquals(40L, report.bytes); assertEquals(2, report.files); assertTrue(report.complete)
        assertFalse(GuideStorageInspector.measureDirectory(root, maxEntries = 1).complete)
        assertEquals(DirectoryStorageEvidence(0, 0, true), GuideStorageInspector.measureDirectory(null))
    }
    @Test fun `depth limited directory is labelled incomplete and cancellation propagates`() {
        val root = temporary.newFolder()
        var dir = root
        repeat(10) { dir = File(dir, "nested").apply { mkdir() } }
        File(dir, "deep").writeBytes(ByteArray(8))
        assertFalse(GuideStorageInspector.measureDirectory(root).complete)
        try {
            GuideStorageInspector.measureDirectory(root) { throw CancellationException("cancel") }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
