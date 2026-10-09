package direct.kabu.alertkun.monitoring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated files only: no app settings, accounts, credentials, workers, alarms or real mail. */
@RunWith(AndroidJUnit4::class)
class ObservationStorageTest {
    @Test fun privateStorageReopenAndRotationPreserveCompleteEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "observation-storage-test-${UUID.randomUUID()}")
        try {
            val first = RotatingObservationLog(dir, maxBytes = 256)
            assertTrue(first.record("FCM_RECEIVED", mapOf("priority" to "NORMAL")))
            val reopened = RotatingObservationLog(dir, maxBytes = 256)
            assertTrue(reopened.record("WORKER_START", mapOf("trigger" to "SCHEDULED_WORK")))
            assertEquals(2, File(dir, "monitoring-observation.log").readLines().size)
            repeat(30) { assertTrue(reopened.record("WORKER_END", mapOf("runAttemptCount" to it))) }
            assertEquals(4, dir.listFiles()!!.size)
            assertTrue(dir.listFiles()!!.all { it.length() <= 256 && it.readLines().all { line -> line.matches(Regex("\\S+(Z|[+-]\\d{2}:\\d{2}) [A-Z_]+.*")) } })
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
}
