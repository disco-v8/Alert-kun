package direct.kabu.alertkun.monitoring

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in observation only. PC controls temporary connectivity; no mail/UI/settings writes here. */
class NetworkCallbackDeviceObservationTest {
    @Test fun currentMonitoringReservationIsPersisted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context).settings.first()
        assertTrue("Monitoring must remain ON", settings.monitoringEnabled)
        val generation = MonitoringStatusRepository(context).executionStatus().scheduleGeneration
        val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork("alertkun_mail_check_periodic").get()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        assertEquals(1, active.size)
        assertTrue(active.single().tags.contains("backup_generation_$generation"))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("observationReservation", "monitoring=ON generation=$generation activePeriodic=1 state=${active.single().state}")
        })
    }

    @Test fun observeTemporaryNetworkTransitions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("observationNetworkProbe")
        assumeTrue(!label.isNullOrBlank())
        val context = instrumentation.targetContext
        val seconds = InstrumentationRegistry.getArguments().getString("observationProbeSeconds")?.toIntOrNull()?.coerceIn(5, 120) ?: 60
        val settingsBefore = SettingsRepository(context).settings.first()
        val generationBefore = MonitoringStatusRepository(context).executionStatus().scheduleGeneration
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        DefaultNetworkMonitor(context) { event, fields ->
            MonitoringObservation.record(context, event, "workId" to "callback-probe-$label", "trigger" to "INSTRUMENTATION",
                *fields.toList().toTypedArray())
            if (event == "NETWORK_CALLBACK") counts.merge(fields["callbackType"].toString(), 1, Int::plus)
        }.use {
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("observationProbe", "READY:$label") })
            delay(seconds * 1000L)
        }
        assertEquals(settingsBefore, SettingsRepository(context).settings.first())
        val generation = MonitoringStatusRepository(context).executionStatus().scheduleGeneration
        assertEquals(generationBefore, generation)
        if (settingsBefore.monitoringEnabled) {
            val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork("alertkun_mail_check_periodic").get()
                .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            assertEquals(1, active.size)
            assertTrue(active.single().tags.contains("backup_generation_$generation"))
        }
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("observationProbe", "CLOSED:$label:$counts") })
        assertTrue(counts.getOrDefault("AVAILABLE", 0) > 0)
        assertTrue(counts.getOrDefault("CAPABILITIES_CHANGED", 0) > 0)
        assertTrue(counts.getOrDefault("LINK_PROPERTIES_CHANGED", 0) > 0)
    }
}
