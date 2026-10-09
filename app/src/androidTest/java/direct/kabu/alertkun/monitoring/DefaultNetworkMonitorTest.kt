package direct.kabu.alertkun.monitoring

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No network/settings changes, no Room/credential access. */
class DefaultNetworkMonitorTest {
    @Test fun injectedDnsFailureRetriesWithoutNetworkChangeAndPersistsDecision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = object : DefaultNetworkSource {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(DefaultNetworkState("fixture", true, true))
        }
        for (successOn in listOf(2, 3, 4)) {
            var calls = 0
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            val recover = successOn <= 3
            val workId = "fixture-dns-three-attempts-$successOn"
            // Isolated state: never modify the user's monitoring counters or reservations.
            val preferences = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
                override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                    transform(data.value).also { data.value = it }
            }
            val status = direct.kabu.alertkun.data.repository.MonitoringStatusRepository.forStore(preferences)
            status.recordCheckStarted(System.currentTimeMillis(), 0)
            val started = android.os.SystemClock.elapsedRealtime()
            val policy = NetworkStabilization(source, observation = { event, fields ->
                events += event to fields
                MonitoringObservation.record(context, event, "workId" to workId, "trigger" to "INSTRUMENTATION",
                    *fields.toList().toTypedArray())
            })
            try {
                assertEquals(42, policy.read {
                    calls++
                    assertEquals(0, status.current().consecutiveFailureCount)
                    if (calls < successOn) throw direct.kabu.alertkun.mail.common.MailReceiverException(
                        MonitoringFailureKind.DNS_FAILURE, java.net.UnknownHostException())
                    42
                })
                assertTrue(recover)
            } catch (error: direct.kabu.alertkun.mail.common.MailReceiverException) {
                assertFalse(recover)
                assertEquals(MonitoringFailureKind.DNS_FAILURE, error.failureKind)
            }
            assertEquals(minOf(successOn, 3), calls)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertTrue(elapsed >= if (successOn == 2) 2_900 else 7_800)
            assertEquals(0, status.current().consecutiveFailureCount)
            if (recover) status.recordSuccess(System.currentTimeMillis(), MailCheckTrigger.FCM, 0)
            else status.recordFailure(System.currentTimeMillis(), 3, 0)
            assertEquals(if (recover) 0 else 1, status.current().consecutiveFailureCount)
            MonitoringObservation.record(context, "FIXTURE_CHECK_OUTCOME", "workId" to workId,
                "trigger" to "INSTRUMENTATION", "attempt" to calls, "maxAttempts" to 3,
                "result" to if (recover) "SUCCESS" else "FAILURE", "count" to status.current().consecutiveFailureCount,
                "elapsedMs" to elapsed)
            assertEquals(false, events.first { it.first == "NETWORK_RETRY_ALLOWED" }.second["networkChanged"])
            val lines = java.io.File(context.filesDir, "observability").listFiles()!!.flatMap { it.readLines() }
                .filter { it.contains("workId=$workId") }
            assertTrue(lines.any { it.contains("NETWORK_RETRY_ALLOWED") && it.contains("category=DNS_FAILURE") && it.contains("networkChanged=false") })
            assertTrue(lines.any { it.contains("NETWORK_RETRY_END") && it.contains("attempt=$calls") && it.contains("result=${if (recover) "SUCCESS" else "FAILURE"}") })
        }
    }

    @Test fun callbackSnapshotAndRepeatedCloseAreSafe() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val monitor = DefaultNetworkMonitor(context)
        try {
            org.junit.Assume.assumeTrue(monitor.awaitReady(8_000))
            assertNotNull(monitor.state.value.networkId)
            assertTrue(monitor.state.value.internet)
            assertTrue(monitor.state.value.validated)
        } finally {
            monitor.close()
            monitor.close()
        }
        val closedState = monitor.state.value
        assertEquals(closedState, monitor.state.value)
    }

    @Test fun cancelledCheckClosesItsCallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var monitor: DefaultNetworkMonitor? = null
        try {
            DefaultNetworkMonitor(context).use {
                monitor = it
                throw kotlinx.coroutines.CancellationException("test cleanup")
            }
        } catch (_: kotlinx.coroutines.CancellationException) { }
        checkNotNull(monitor).close()
    }
}
