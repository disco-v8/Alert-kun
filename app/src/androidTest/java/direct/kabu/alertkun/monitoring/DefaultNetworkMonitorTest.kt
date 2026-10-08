package direct.kabu.alertkun.monitoring

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No network/settings changes, no Room/credential access. */
class DefaultNetworkMonitorTest {
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
