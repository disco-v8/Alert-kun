package direct.kabu.alertkun.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.monitoring.MailCheckWorker
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Enqueues the authorized one-time check without editing real account/settings data. */
class FcmMailCheckHandoffTest {
    @Test fun runNowEnqueuesExistingWorkerAndPreservesSettingsAndGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        val status = MonitoringStatusRepository(context)
        val beforeSettings = settings.settings.first()
        val beforeStatus = status.executionStatus()
        var enqueues = 0
        FcmMailCheckDispatcher {
            enqueues++
            MonitoringScheduler.runNow(context, MailCheckTrigger.FCM).result.get(10, TimeUnit.SECONDS)
        }.dispatch(mapOf("action" to "CHECK_MAIL"))
        val works = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MonitoringScheduler.FCM_WORK_NAME).get(10, TimeUnit.SECONDS)
        assertEquals(1, enqueues)
        assertTrue(works.isNotEmpty())
        assertTrue(works.all { MailCheckWorker::class.java.name in it.tags })
        assertTrue(works.all { "mail_check_FCM" in it.tags })
        assertEquals(beforeSettings, settings.settings.first())
        val afterStatus = status.executionStatus()
        assertEquals(beforeStatus.scheduleGeneration, afterStatus.scheduleGeneration)
        assertEquals(beforeStatus.monitoringEnabled, afterStatus.monitoringEnabled)
        assertEquals(beforeStatus.scheduleKind, afterStatus.scheduleKind)
    }
}
