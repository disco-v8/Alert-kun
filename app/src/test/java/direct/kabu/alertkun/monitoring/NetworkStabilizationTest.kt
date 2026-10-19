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
    private fun policy(source: DefaultNetworkSource, elapsedMillis: () -> Long = { 0L }, observation: ObservationSink = { _, _ -> }, pause: suspend (Long) -> Unit = {}) = NetworkStabilization(source, elapsedMillis = elapsedMillis, observation = observation, pause = pause)
    private fun socketError() = MailReceiverException(MonitoringFailureKind.SERVER_UNAVAILABLE, SocketException("Fixture reset"))

    @Test fun validatedNetworkConnectsImmediately() = runBlocking {
        val source = Source(usable)
        assertEquals(42, policy(source).read { 42 })
        assertEquals(0, source.waits)
    }
    @Test fun noNetworkWaitsThenRestorationAllowsRealConnection() = runBlocking {
        val source = Source(DefaultNetworkState()).apply { onWait = { state.value = usable; true } }
        var calls = 0
        assertEquals(42, policy(source).read { calls++; 42 })
        assertEquals(1, source.waits); assertEquals(1, calls)
    }
    @Test fun missingInternetOrValidationWaits() = runBlocking {
        for (snapshot in listOf(usable.copy(internet = false), usable.copy(validated = false))) {
            val source = Source(snapshot).apply { onWait = { state.value = usable; true } }
            assertEquals(42, policy(source).read { 42 })
            assertEquals(1, source.waits)
        }
    }
    @Test fun absentNetworkAfterTimeoutProducesOfflineWithoutConnecting() = runBlocking {
        val source = Source(DefaultNetworkState())
        try { policy(source).read { error("Must not connect") }; fail() }
        catch (error: MailReceiverException) { assertEquals(MonitoringFailureKind.DEVICE_OFFLINE, error.failureKind) }
        assertEquals(1, source.waits)
    }
    @Test fun unvalidatedNetworkAfterTimeoutStillLetsActualConnectionDecide() = runBlocking {
        val source = Source(usable.copy(validated = false))
        assertEquals(42, policy(source).read { 42 })
        assertEquals(1, source.waits)
    }
    @Test fun callbackFlowResumesImmediatelyOnValidation() = runBlocking {
        val flow = MutableStateFlow(usable.copy(validated = false))
        val source = object : DefaultNetworkSource { override val state = flow }
        val result = async { policy(source).read { 42 } }
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
        assertEquals(42, policy(source).read {
            attempts++
            if (attempts == 1) { source.state.value = usable.copy(networkId = "new", changeVersion = 1); throw socketError() }
            42
        })
        assertEquals(2, attempts)
    }
    @Test fun retryWaitsForNewValidatedNetwork() = runBlocking {
        val source = Source(usable).apply { onWait = { state.value = state.value.copy(validated = true); true } }
        var attempts = 0
        policy(source).read {
            attempts++
            if (attempts == 1) { source.state.value = usable.copy(networkId = "new", validated = false, changeVersion = 1); throw socketError() }
        }
        assertEquals(2, attempts); assertEquals(1, source.waits)
    }
    @Test fun retryFailurePropagatesAndNeverMakesFourthAttempt() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        try { policy(source).read { attempts++; source.state.value = usable.copy(changeVersion = attempts.toLong()); throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(3, attempts)
    }
    @Test fun unchangedNetworkRetriesCommunicationFailureOnlyTwice() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        try { policy(source).read { attempts++; throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(3, attempts)
    }
    private fun dnsFailureRetriesOnce(changed: Boolean) = runBlocking {
        val source = Source(usable)
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var attempts = 0
        val result = policy(source, observation = { event, fields -> events += event to fields }).read {
            attempts++
            if (attempts == 1) {
                if (changed) source.state.value = usable.copy(changeVersion = 1)
                throw MailReceiverException(MonitoringFailureKind.DNS_FAILURE, java.net.UnknownHostException())
            }
            42
        }
        assertEquals(42, result)
        assertEquals(2, attempts)
        assertEquals(0, source.waits)
        val allowed = events.single { it.first == "NETWORK_RETRY_ALLOWED" }.second
        assertEquals(changed, allowed["networkChanged"])
        assertEquals("DNS_FAILURE", allowed["category"])
        assertEquals(false, allowed["retryUsed"])
        assertEquals(0, allowed["retryNumber"])
        assertEquals(true, allowed["validated"])
        assertEquals(1, events.single { it.first == "NETWORK_RETRY_START" }.second["retryNumber"])
        assertEquals("SUCCESS", events.single { it.first == "NETWORK_RETRY_END" }.second["result"])
    }
    @Test fun dnsFailureWithoutNetworkChangeRetriesOnce() = dnsFailureRetriesOnce(false)
    @Test fun dnsFailureWithNetworkChangeRetriesOnce() = dnsFailureRetriesOnce(true)
    @Test fun repeatedDnsFailureLogsSkippedThirdRetryAndFailure() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var attempts = 0
        try {
            policy(Source(usable), observation = { event, fields -> events += event to fields }).read {
                attempts++
                throw MailReceiverException(MonitoringFailureKind.DNS_FAILURE, java.net.UnknownHostException())
            }
            fail()
        } catch (_: MailReceiverException) { }
        assertEquals(3, attempts)
        val skipped = events.single { it.first == "NETWORK_RETRY_SKIPPED" }.second
        assertEquals(true, skipped["retryUsed"])
        assertEquals(2, skipped["retryNumber"])
        assertEquals(3, skipped["attempt"])
        assertEquals(3, skipped["maxAttempts"])
        assertEquals(false, skipped["networkChanged"])
        assertEquals("FAILURE", events.last { it.first == "NETWORK_RETRY_END" }.second["result"])
    }
    @Test fun permanentErrorsAndLocalIoNeverRetryEvenWithTransportCause() = runBlocking {
        val errors = listOf(
            jakarta.mail.AuthenticationFailedException(),
            javax.net.ssl.SSLHandshakeException("Fixture"),
            java.security.cert.CertificateException(),
            MailReceiverException(MonitoringFailureKind.HOSTNAME_MISMATCH, SocketException()),
            MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR, SocketException()),
            java.io.IOException("Fixture local IO"),
        )
        for (error in errors) {
            var calls = 0
            try { policy(Source(usable)).read { calls++; throw error }; fail() }
            catch (actual: Exception) { assertSame(error, actual) }
            assertEquals(1, calls)
        }
    }
    @Test fun typedTimeoutUnreachableAndResetRetryWithoutNetworkChange() = runBlocking {
        for (error in listOf(java.net.SocketTimeoutException(), java.net.NoRouteToHostException(), SocketException())) {
            var calls = 0
            assertEquals(42, policy(Source(usable)).read { calls++; if (calls == 1) throw error; 42 })
            assertEquals(2, calls)
        }
    }
    @Test fun authTlsCertificatesAndProtocolAreNeverRetryCandidates() {
        for (kind in MonitoringFailureKind.entries.filter { it !in setOf(MonitoringFailureKind.DNS_FAILURE,
            MonitoringFailureKind.CONNECTION_TIMEOUT, MonitoringFailureKind.CONNECTION_REFUSED, MonitoringFailureKind.SERVER_UNAVAILABLE) }) {
            assertFalse(NetworkStabilization.isTransientNetworkRetryCandidate(MailReceiverException(kind, SocketException())))
        }
        assertFalse(NetworkStabilization.isTransientNetworkRetryCandidate(javax.net.ssl.SSLException("Fixture")))
        assertFalse(NetworkStabilization.isTransientNetworkRetryCandidate(java.security.cert.CertificateException()))
        assertFalse(NetworkStabilization.isTransientNetworkRetryCandidate(jakarta.mail.AuthenticationFailedException()))
    }
    @Test fun dnsTimeoutResetAndEofAreCandidatesOnlyWithCommunicationEvidence() {
        assertTrue(NetworkStabilization.isTransientNetworkRetryCandidate(java.net.UnknownHostException()))
        assertTrue(NetworkStabilization.isTransientNetworkRetryCandidate(java.net.SocketTimeoutException()))
        assertTrue(NetworkStabilization.isTransientNetworkRetryCandidate(java.net.ConnectException()))
        assertTrue(NetworkStabilization.isTransientNetworkRetryCandidate(java.io.EOFException()))
        assertFalse(NetworkStabilization.isTransientNetworkRetryCandidate(IllegalStateException()))
    }
    @Test fun waitBudgetIsSharedAcrossInitialWaitAndRetry() = runBlocking {
        var elapsed = 0L
        val budgets = mutableListOf<Long>()
        val source = Source(usable.copy(validated = false)).apply {
            onWait = { budgets += it; elapsed += 3_000; state.value = state.value.copy(validated = true); true }
        }
        val stabilization = policy(source, elapsedMillis = { elapsed })
        var attempts = 0
        stabilization.read {
            attempts++
            if (attempts == 1) { source.state.value = usable.copy(networkId = "new", validated = false, changeVersion = 1); throw socketError() }
        }
        assertEquals(listOf(8_000L, 2_000L), budgets)
    }
    @Test fun retryBudgetIsSharedAcrossReadOperations() = runBlocking {
        val source = Source(usable)
        val stabilization = policy(source)
        var calls = 0
        stabilization.read { calls++; if (calls == 1) { source.state.value = usable.copy(changeVersion = 1); throw socketError() } }
        try { stabilization.read { calls++; source.state.value = usable.copy(changeVersion = 2); throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(4, calls)
    }
    @Test fun retryWaitsThreeThenFiveSecondsWithinSharedBudget() = runBlocking {
        val waits = mutableListOf<Long>()
        var calls = 0
        assertEquals(42, policy(Source(usable), pause = { waits += it }).read {
            calls++
            if (calls < 3) throw socketError()
            42
        })
        assertEquals(3, calls)
        assertEquals(listOf(3_000L, 5_000L), waits)
    }
    @Test fun initialStabilizationConsumesRetryWaitingBudget() = runBlocking {
        var elapsed = 0L
        val waits = mutableListOf<Long>()
        val source = Source(usable.copy(validated = false)).apply {
            onWait = { elapsed += 2_000; state.value = usable; true }
        }
        var calls = 0
        assertEquals(42, policy(source, elapsedMillis = { elapsed }, pause = { waits += it; elapsed += it }).read {
            calls++
            if (calls < 3) throw socketError()
            42
        })
        assertEquals(listOf(3_000L, 3_000L), waits)
        assertEquals(8_000L, elapsed)
    }
    @Test fun exhaustedWaitBudgetNeverCreatesImmediateRetry() = runBlocking {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val source = Source(usable.copy(validated = false))
        var calls = 0
        try { policy(source, observation = { event, fields -> events += event to fields }).read { calls++; throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(1, calls)
        assertEquals("WAIT_BUDGET_EXHAUSTED", events.single { it.first == "NETWORK_RETRY_SKIPPED" }.second["reason"])
    }
    @Test fun tinyRemainingBudgetNeverCreatesMillisecondRetry() = runBlocking {
        var elapsed = 0L
        val source = Source(usable.copy(validated = false)).apply {
            onWait = { elapsed += 7_990; state.value = usable; true }
        }
        var calls = 0
        try { policy(source, elapsedMillis = { elapsed }, pause = { error("Must not retry immediately") }).read { calls++; throw socketError() }; fail() }
        catch (_: MailReceiverException) { }
        assertEquals(1, calls)
    }
    @Test fun cancellationDuringRetryDelayNeverStartsNextAttempt() = runBlocking {
        var calls = 0
        try { policy(Source(usable), pause = { throw CancellationException() }).read { calls++; throw socketError() }; fail() }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
    @Test fun coordinatorReportsOnlyOneFinalCheckOutcomeAcrossThreeCommunicationAttempts() = runBlocking {
        for (successOn in listOf(1, 2, 3, 4)) {
            val preferences = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
                override val data = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
                override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                    transform(data.value).also { data.value = it }
            }
            val status = direct.kabu.alertkun.data.repository.MonitoringStatusRepository.forStore(preferences)
            val generation = status.changeSchedule(true, 1_700_000_000_000L, "PERIODIC", 15)
            status.recordCheckStarted(1_700_000_000_000L, generation)
            var calls = 0
            val receiver = object : MailReceiver {
                override suspend fun fetchNewMessages(): List<RemoteMail> {
                    calls++
                    assertEquals(0, status.current().consecutiveFailureCount)
                    if (calls < successOn) throw MailReceiverException(MonitoringFailureKind.DNS_FAILURE, java.net.UnknownHostException())
                    return emptyList()
                }
                override suspend fun fetchBody(remoteMail: RemoteMail): String? = null
            }
            val store = object : MonitoringMailStore {
                override suspend fun receivedMails() = emptyList<ReceivedMail>()
                override suspend fun saveReceived(mail: ReceivedMail) = error("No fixture mail")
                override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) = error("No fixture mail")
            }
            val coordinator = MailCheckCoordinator(store, object : MonitoringRuleSource {
                override suspend fun allRules() = emptyList<AlertRule>()
            }, NetworkStabilizingMailReceiver(receiver, policy(Source(usable)), hasDeletionConnection = false),
                object : MonitoringAlarmSink { override fun start(event: direct.kabu.alertkun.alarm.AlarmEvent) = error("No fixture alarm") })
            val result = coordinator.check()
            assertEquals(successOn <= 3, result.success)
            assertEquals(minOf(successOn, 3), calls)
            assertEquals(0, status.current().consecutiveFailureCount)
            // The unchanged Worker records exactly this final Coordinator outcome once.
            if (result.success) status.recordSuccess(1_700_000_000_001L, MailCheckTrigger.FCM, generation)
            else status.recordFailure(1_700_000_000_001L, 3, generation)
            assertEquals(if (result.success) 0 else 1, status.current().consecutiveFailureCount)
        }
    }
    @Test fun cancellationIsNeverRetried() = runBlocking {
        val source = Source(usable)
        var calls = 0
        try { policy(source).read { calls++; source.state.value = usable.copy(changeVersion = 1); throw CancellationException() }; fail() }
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
            override suspend fun knownRemoteIds(accountId: String) = rows.values.filter { it.accountId==accountId }.map { it.remoteMessageId }.toSet()
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
            override suspend fun receive(knownIds:suspend(String)->Set<String>,consume:suspend(RemoteMail)->Unit) {
                val known=knownIds("a")
                for(id in listOf("one","two")) if(id !in known) {
                    bodyCalls++
                    if(id=="two" && bodyCalls==2) {
                        source.state.value=usable.copy(networkId="new",changeVersion=1)
                        throw socketError()
                    }
                    consume(RemoteMail("a",id,"Other",null,body="ALERT",bodyFetched=true))
                }
            }
            override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) { deletions++ }
        }
        val coordinator = MailCheckCoordinator(store, object : MonitoringRuleSource {
            override suspend fun allRules() = listOf(AlertRule("r", "Fixture", "ALERT", RuleTarget.SUBJECT_AND_BODY, 1,
                null, VibrationPattern.NONE, AlarmMode.ONCE, true))
        }, NetworkStabilizingMailReceiver(receiver, policy(source)),
            object : MonitoringAlarmSink { override fun start(event: direct.kabu.alertkun.alarm.AlarmEvent) { alarms++ } })
        assertTrue(coordinator.check().success)
        assertEquals(3,bodyCalls) // First save is not re-fetched during the in-poll retry.
        assertEquals(2, rows.size); assertEquals(1, alarms); assertEquals(1, deletions)
        assertTrue(rows.values.all { it.processingState == MailProcessingState.PROCESSED })
        assertTrue(coordinator.check().success)
        assertEquals(2, rows.size); assertEquals(1, alarms)
    }
    @Test fun angusAuthenticationExceptionWithoutTransportCauseIsNotRetried() = runBlocking {
        val source = Source(usable)
        var attempts = 0
        try {
            policy(source).read {
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
        NetworkStabilizingMailReceiver(receiver, policy(source), hasDeletionConnection = false)
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
        try { NetworkStabilizingMailReceiver(receiver, policy(source)).deleteProcessedMessages({ _, _ -> null }, 0); fail() }
        catch (_: MailReceiverException) { }
        assertEquals(1, deletions)
    }
}
