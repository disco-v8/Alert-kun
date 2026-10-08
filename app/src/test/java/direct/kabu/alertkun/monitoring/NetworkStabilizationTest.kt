package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.*
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NetworkStabilizationTest {
    private val usable = DefaultNetworkState("old", true, true)
    private class Source(initial: DefaultNetworkState) : DefaultNetworkSource {
        override val state = MutableStateFlow(initial)
        var waits = 0
        var onWait: suspend (Long) -> Boolean = { false }
        override suspend fun awaitReady(timeoutMillis: Long): Boolean { waits++; return onWait(timeoutMillis) }
    }
    private fun socketError() = MailReceiverException(MonitoringFailureKind.SERVER_UNAVAILABLE, SocketException("Fixture reset"))

    @Test fun validatedNetworkConnectsImmediately() = runBlocking {
        val source = Source(usable)
        assertEquals(42, NetworkStabilization(source).read { 42 })
        assertEquals(0, source.waits)
    }
    @Test fun noNetworkWaitsThenRestorationAllowsRealConnection() = runBlocking {
        val source = Source(DefaultNetworkState()).apply { onWait = { state.value = usable; true } }
        var calls = 0
        assertEquals(42, NetworkStabilization(source).read { calls++; 42 })
        assertEquals(1, source.waits); assertEquals(1, calls)
    }
    @Test fun missingInternetOrValidationWaits() = runBlocking {
        for (snapshot in listOf(usable.copy(internet = false), usable.copy(validated = false))) {
            val source = Source(snapshot).apply { onWait = { state.value = usable; true } }
            assertEquals(42, NetworkStabilization(source).read { 42 })
            assertEquals(1, source.waits)
        }
    }
    @Test fun absentNetworkAfterTimeoutProducesOfflineWithoutConnecting() = runBlocking {
        val source = Source(DefaultNetworkState())
        try { NetworkStabilization(source).read { error("Must not connect") }; fail() }
        catch (error: MailReceiverException) { assertEquals(MonitoringFailureKind.DEVICE_OFFLINE, error.failureKind) }
        assertEquals(1, source.waits)
    }
    @Test fun unvalidatedNetworkAfterTimeoutStillLetsActualConnectionDecide() = runBlocking {
        val source = Source(usable.copy(validated = false))
        assertEquals(42, NetworkStabilization(source).read { 42 })
        assertEquals(1, source.waits)
    }
    @Test fun callbackFlowResumesImmediatelyOnValidation() = runBlocking {
        val flow = MutableStateFlow(usable.copy(validated = false))
        val source = object : DefaultNetworkSource { override val state = flow }
        val result = async { NetworkStabilization(source).read { 42 } }
        yield(); assertFalse(result.isCompleted)
        flow.value = usable
        assertEquals(42, result.await())
    }
    @Test fun callbackWaitIsFiniteWithoutRestoration() = runBlocking {
        val source = object : DefaultNetworkSource { override val state = MutableStateFlow(DefaultNetworkState()) }
        assertFalse(source.awaitReady(1))
    }
    @Test fun changedDefaultNetworkAndSocketErrorRetryOnceAndSucceed() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        assertEquals(42, NetworkStabilization(source).read {
            attempts++
            if (attempts == 1) { source.state.value = usable.copy(networkId = "new", changeVersion = 1); throw socketError() }
            42
        })
        assertEquals(2, attempts)
    }
    @Test fun retryWaitsForNewValidatedNetwork() = runBlocking {
        val source = Source(usable).apply { onWait = { state.value = state.value.copy(validated = true); true } }
        var attempts = 0
        NetworkStabilization(source).read {
            attempts++
            if (attempts == 1) { source.state.value = usable.copy(networkId = "new", validated = false, changeVersion = 1); throw socketError() }
        }
        assertEquals(2, attempts); assertEquals(1, source.waits)
    }
    @Test fun retryFailurePropagatesAndNeverMakesThirdAttempt() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        try { NetworkStabilization(source).read { attempts++; source.state.value = usable.copy(changeVersion = attempts.toLong()); throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(2, attempts)
    }
    @Test fun unchangedNetworkNeverRetriesCommunicationFailure() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        try { NetworkStabilization(source).read { attempts++; throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(1, attempts)
    }
    @Test fun authTlsCertificatesAndProtocolAreNeverRetryCandidates() {
        for (kind in MonitoringFailureKind.entries.filter { it !in setOf(MonitoringFailureKind.DNS_FAILURE,
            MonitoringFailureKind.CONNECTION_TIMEOUT, MonitoringFailureKind.CONNECTION_REFUSED, MonitoringFailureKind.SERVER_UNAVAILABLE) }) {
            assertFalse(NetworkStabilization.isTransitionRetryCandidate(MailReceiverException(kind, SocketException())))
        }
        assertFalse(NetworkStabilization.isTransitionRetryCandidate(javax.net.ssl.SSLException("Fixture")))
        assertFalse(NetworkStabilization.isTransitionRetryCandidate(java.security.cert.CertificateException()))
        assertFalse(NetworkStabilization.isTransitionRetryCandidate(jakarta.mail.AuthenticationFailedException()))
    }
    @Test fun dnsTimeoutResetAndEofAreCandidatesOnlyWithCommunicationEvidence() {
        assertTrue(NetworkStabilization.isTransitionRetryCandidate(java.net.UnknownHostException()))
        assertTrue(NetworkStabilization.isTransitionRetryCandidate(java.net.SocketTimeoutException()))
        assertTrue(NetworkStabilization.isTransitionRetryCandidate(java.net.ConnectException()))
        assertTrue(NetworkStabilization.isTransitionRetryCandidate(java.io.EOFException()))
        assertFalse(NetworkStabilization.isTransitionRetryCandidate(IllegalStateException()))
    }
    @Test fun waitBudgetIsSharedAcrossInitialWaitAndRetry() = runBlocking {
        var elapsed = 0L
        val budgets = mutableListOf<Long>()
        val source = Source(usable.copy(validated = false)).apply {
            onWait = { budgets += it; elapsed += 3_000; state.value = state.value.copy(validated = true); true }
        }
        val stabilization = NetworkStabilization(source, elapsedMillis = { elapsed })
        var attempts = 0
        stabilization.read {
            attempts++
            if (attempts == 1) { source.state.value = usable.copy(networkId = "new", validated = false, changeVersion = 1); throw socketError() }
        }
        assertEquals(listOf(8_000L, 5_000L), budgets)
    }
    @Test fun retryBudgetIsSharedAcrossReadOperations() = runBlocking {
        val source = Source(usable)
        val stabilization = NetworkStabilization(source)
        var calls = 0
        stabilization.read { calls++; if (calls == 1) { source.state.value = usable.copy(changeVersion = 1); throw socketError() } }
        try { stabilization.read { calls++; source.state.value = usable.copy(changeVersion = 2); throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(3, calls)
    }
    @Test fun cancellationIsNeverRetried() = runBlocking {
        val source = Source(usable)
        var calls = 0
        try { NetworkStabilization(source).read { calls++; source.state.value = usable.copy(changeVersion = 1); throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
    @Test fun retryDoesNotReplaySavedMailRuleAlarmOrDeletion() = runBlocking {
        val source = Source(usable)
        var bodyCalls = 0
        var alarms = 0
        var deletions = 0
        val rows = linkedMapOf<String, ReceivedMail>()
        val store = object : MonitoringMailStore {
            override suspend fun receivedMails() = rows.values.filter { it.processingState == MailProcessingState.RECEIVED }
            override suspend fun saveReceived(mail: ReceivedMail): Boolean {
                if (rows.values.any { it.remoteMessageId == mail.remoteMessageId }) return false
                rows[mail.id] = mail; return true
            }
            override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
                rows[id] = rows.getValue(id).copy(processingState = MailProcessingState.PROCESSED, alertTriggered = alertTriggered)
            }
        }
        val receiver = object : MailReceiver {
            override suspend fun fetchNewMessages() = listOf(RemoteMail("a", "one", "ALERT", null, emptyMap()),
                RemoteMail("a", "two", "Other", null, emptyMap()))
            override suspend fun fetchBody(remoteMail: RemoteMail): String {
                bodyCalls++
                if (bodyCalls == 1) { source.state.value = usable.copy(networkId = "new", changeVersion = 1); throw socketError() }
                return "ALERT"
            }
            override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) { deletions++ }
        }
        val coordinator = MailCheckCoordinator(store, object : MonitoringRuleSource {
            override suspend fun allRules() = listOf(AlertRule("r", "Fixture", "ALERT", RuleTarget.SUBJECT_AND_BODY, 1,
                null, VibrationPattern.NONE, AlarmMode.ONCE, true))
        }, NetworkStabilizingMailReceiver(receiver, NetworkStabilization(source)),
            object : MonitoringAlarmSink { override fun start(event: direct.kabu.alertkun.alarm.AlarmEvent) { alarms++ } })
        assertTrue(coordinator.check().success)
        assertEquals(2, rows.size); assertEquals(1, alarms); assertEquals(1, deletions)
        assertTrue(rows.values.all { it.processingState == MailProcessingState.PROCESSED })
        assertTrue(coordinator.check().success)
        assertEquals(2, rows.size); assertEquals(1, alarms)
    }
    @Test fun angusAuthenticationExceptionWithoutTransportCauseIsNotRetried() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        try {
            NetworkStabilization(source).read {
                attempts++
                source.state.value = usable.copy(changeVersion = 1)
                throw MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE,
                    jakarta.mail.AuthenticationFailedException("Software caused connection abort"))
            }
            fail()
        } catch (error: MailReceiverException) { assertEquals(MonitoringFailureKind.AUTHENTICATION_FAILURE, error.failureKind) }
        assertEquals(1, attempts)
    }
    @Test fun noDeletionConnectionDoesNotConvertSuccessfulReadToAnOfflineFailure() = runBlocking {
        val source = Source(DefaultNetworkState())
        var calls = 0
        val receiver = object : MailReceiver {
            override suspend fun fetchNewMessages() = emptyList<RemoteMail>()
            override suspend fun fetchBody(remoteMail: RemoteMail): String? = null
            override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) { calls++ }
        }
        NetworkStabilizingMailReceiver(receiver, NetworkStabilization(source), hasDeletionConnection = false)
            .deleteProcessedMessages({ _, _ -> null }, 0)
        assertEquals(1, calls)
        assertEquals(0, source.waits)
    }
    @Test fun uncertainDeletionIsNeverRetriedAfterNetworkChange() = runBlocking {
        val source = Source(usable)
        var deletions = 0
        val receiver = object : MailReceiver {
            override suspend fun fetchNewMessages() = emptyList<RemoteMail>()
            override suspend fun fetchBody(remoteMail: RemoteMail): String? = null
            override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
                deletions++; source.state.value = usable.copy(changeVersion = 1); throw socketError()
            }
        }
        try { NetworkStabilizingMailReceiver(receiver, NetworkStabilization(source)).deleteProcessedMessages({ _, _ -> null }, 0); fail() }
        catch (_: MailReceiverException) { }
        assertEquals(1, deletions)
    }
}
