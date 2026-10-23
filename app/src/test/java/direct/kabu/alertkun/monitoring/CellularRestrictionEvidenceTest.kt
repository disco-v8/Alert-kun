package direct.kabu.alertkun.monitoring

import java.net.SocketTimeoutException
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CellularRestrictionEvidenceTest {
    private val cellular = CheckNetworkSnapshot("fixture-cell", 1, true, true)
    private fun failure(error: Throwable) = MailCheckResult(false, error = error,
        failureKind = MonitoringFailureKind.CONNECTION_TIMEOUT)

    @Test fun onlyFinalTransportFailureOnSameRestrictedCellularReadQualifies() {
        for (network in listOf(cellular, cellular.copy(cellularOnly = false),
                cellular.copy(restricted = false), cellular.copy(networkId = null))) {
            val evidence = CellularRestrictionEvidence()
            val error = SocketTimeoutException()
            evidence.readStarted(network)
            evidence.readFailed(network, error)
            assertEquals(network == cellular, evidence.shouldAdvise(failure(error), network))
            assertFalse(evidence.shouldAdvise(MailCheckResult(true), network))
        }
    }

    @Test fun networkSwitchLossUnknownAndDifferentFailureNeverReuseEvidence() {
        val error = SocketTimeoutException()
        for (other in listOf(cellular.copy(networkId = "fixture-other"), cellular.copy(version = 2),
                cellular.copy(networkId = null), cellular.copy(cellularOnly = false), cellular.copy(restricted = false))) {
            val evidence = CellularRestrictionEvidence()
            evidence.readStarted(cellular)
            evidence.readFailed(other, error)
            assertFalse(evidence.shouldAdvise(failure(error), other))
            evidence.readFailed(cellular, error)
            assertFalse(evidence.shouldAdvise(failure(error), other))
            assertFalse(evidence.shouldAdvise(failure(SocketTimeoutException()), cellular))
        }
    }

    @Test fun authenticationTlsLocalErrorsAndDemoWithoutRealReadAreExcluded() {
        for (error in listOf(AuthenticationFailedException(), javax.net.ssl.SSLException("fixture"),
                java.io.IOException(), IllegalStateException())) {
            val evidence = CellularRestrictionEvidence()
            evidence.readStarted(cellular)
            evidence.readFailed(cellular, error)
            assertFalse(evidence.shouldAdvise(failure(error), cellular))
        }
        assertFalse(CellularRestrictionEvidence().shouldAdvise(failure(SocketTimeoutException()), cellular))
    }

    @Test fun retrySuccessAndNextReadClearWarningWithoutChangingRetryBudget() = runBlocking {
        val evidence = CellularRestrictionEvidence()
        val error = SocketTimeoutException()
        val source = object : DefaultNetworkSource {
            override val state = MutableStateFlow(DefaultNetworkState("fixture-cell", true, true))
            override fun readStarted() = evidence.readStarted(cellular)
            override fun readFailed(error: Throwable) = evidence.readFailed(cellular, error)
            override fun readSucceeded() = evidence.readSucceeded()
        }
        val waits = mutableListOf<Long>()
        val network = NetworkStabilization(source, pause = { waits += it })
        var attempts = 0
        assertEquals("ok", network.read { if (++attempts < 3) throw error else "ok" })
        assertEquals(listOf(3000L, 5000L), waits)
        assertFalse(evidence.shouldAdvise(failure(error), cellular))
        evidence.readStarted(cellular)
        evidence.readFailed(cellular, error)
        assertTrue(evidence.shouldAdvise(failure(error), cellular))
        evidence.readStarted(cellular.copy(restricted = false))
        assertFalse(evidence.shouldAdvise(failure(error), cellular))
    }
}
