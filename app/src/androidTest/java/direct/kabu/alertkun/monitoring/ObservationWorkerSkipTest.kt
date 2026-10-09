package direct.kabu.alertkun.monitoring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Invalid generation guarantees no real check, DataStore write, mail, deletion or alarm. */
@RunWith(AndroidJUnit4::class)
class ObservationWorkerSkipTest {
    @Test fun scheduledGenerationMismatchPersistsSkipAndEndWithoutChangingMonitoring() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val status = MonitoringStatusRepository(context)
        val before = status.executionStatus()
        val settings = SettingsRepository(context).settings.first()
        assertTrue(settings.monitoringEnabled && before.monitoringEnabled)
        assertTrue(before.scheduleGeneration >= 0)
        val request = OneTimeWorkRequestBuilder<MailCheckWorker>().addTag("observation_test")
            .setInputData(workDataOf(MonitoringScheduler.KEY_SCHEDULED to true,
                MonitoringScheduler.KEY_GENERATION to -1L,
                MonitoringScheduler.KEY_TRIGGER to MailCheckTrigger.SCHEDULED_WORK.name)).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
        val result = withTimeout(10_000) {
            var info = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!
            while (!info.state.isFinished) {
                delay(25)
                info = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!
            }
            info
        }
        assertEquals(WorkInfo.State.SUCCEEDED, result.state)
        val lines = File(context.filesDir, "observability").listFiles()!!.flatMap { it.readLines() }
            .filter { it.contains("workId=${request.id}") }
        assertTrue(lines.any { it.contains("WORKER_START") && it.contains("trigger=SCHEDULED_WORK") })
        assertTrue(lines.any { it.contains("MUTEX_WAIT") })
        assertTrue(lines.any { it.contains("MUTEX_ACQUIRED") })
        assertTrue(lines.any { it.contains("WORKER_SKIP") && it.contains("skipReason=GENERATION_MISMATCH") && it.contains("inputGeneration=-1") })
        assertTrue(lines.any { it.contains("WORKER_END") && it.contains("checkResult=SKIPPED") })
        assertFalse(lines.any { it.contains("CHECK_START") || it.contains("MAIL_CONNECT_START") })
        assertEquals(settings, SettingsRepository(context).settings.first())
        assertEquals(before.scheduleGeneration, status.executionStatus().scheduleGeneration)
        assertEquals(before.monitoringEnabled, status.executionStatus().monitoringEnabled)
    }
}
