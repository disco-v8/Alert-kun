package direct.kabu.alertkun.monitoring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in device test setup/restore through the ordinary monitoring lifecycle. */
class MonitoringResultDevicePreparationTest {
    @Test fun applyRequestedMonitoringStateWithoutChangingOtherSettings() = runBlocking {
        val requested = InstrumentationRegistry.getArguments().getString("ruleStatusMonitoring")
        assumeTrue(requested in listOf("ON", "OFF"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(app)
        val before = repository.settings.first()
        val enabled = requested == "ON"
        MonitoringDevicePreparation.apply(app, before, enabled)
        assertEquals(before.copy(monitoringEnabled = enabled), repository.settings.first())
        if (!enabled) {
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (android.os.SystemClock.elapsedRealtime() < deadline && manager.activeNotifications.any { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
                kotlinx.coroutines.delay(20)
            assertEquals(0, manager.activeNotifications.count { it.id == MonitoringStatusNotifier.NOTIFICATION_ID })
        }
    }
}
