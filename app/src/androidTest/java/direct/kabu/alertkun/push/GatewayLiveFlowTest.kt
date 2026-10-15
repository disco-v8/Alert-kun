package direct.kabu.alertkun.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.monitoring.MonitoringDevicePreparation
import direct.kabu.alertkun.monitoring.observationTestLines
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in normal Gateway/FCM check; restores original monitoring settings in finally. */
class GatewayLiveFlowTest {
    @Test fun normalFcmCompletesMailCheckWithStoredBearer() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gatewayLiveMonitoring") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(app)
        val before = settings.settings.first()
        val log = File(app.filesDir, "observability")
        val existingLines = observationTestLines(log).size
        FcmRegistrationCoordinator.start(app)
        try {
            if (!before.monitoringEnabled) MonitoringDevicePreparation.apply(app, before, true)
            val timeoutAt = android.os.SystemClock.elapsedRealtime() + 9 * 60_000L
            var completed = false
            while (android.os.SystemClock.elapsedRealtime() < timeoutAt) {
                val lines = observationTestLines(log).drop(existingLines)
                val end = lines.lastOrNull { it.contains(" WORKER_END ") && it.contains("trigger=FCM") && it.contains("checkResult=SUCCESS") }
                if (end != null) {
                    val id = Regex("workId=([^ ]+)").find(end)!!.groupValues[1]
                    val check = lines.filter { it.contains("workId=$id ") }
                    assertTrue(check.any { it.contains(" CHECK_START ") })
                    assertTrue(check.any { it.contains(" MAIL_CONNECT_SUCCESS ") })
                    assertTrue(check.any { it.contains(" CHECK_SUCCESS ") })
                    assertTrue(lines.any { it.contains(" FCM_RECEIVED ") })
                    assertTrue(lines.any { it.contains(" GATEWAY_SET_INTERVAL_SUCCESS ") && it.contains("httpStatus=200") })
                    assertFalse("Stored bearer avoids primary auth", lines.any { it.contains(" GATEWAY_AUTH_START ") })
                    completed = true
                    break
                }
                delay(1000)
            }
            assertTrue("Natural FCM completed a real mail check", completed)
        } finally {
            if (!before.monitoringEnabled) MonitoringDevicePreparation.apply(app, before, before.monitoringEnabled)
        }
        assertEquals(before, settings.settings.first())
    }
}
