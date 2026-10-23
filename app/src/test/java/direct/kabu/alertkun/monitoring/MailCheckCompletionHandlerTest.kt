package direct.kabu.alertkun.monitoring

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.*
import java.net.SocketException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class MailCheckCompletionHandlerTest {
    @Test fun displayOnlyRestrictionHintPreservesAccountingAndClearsOnRecovery() = runBlocking {
        val f = Fixture()
        val failed = f.handler.complete(failure, 100L, 3, MailCheckTrigger.FCM, 0, true)
        assertEquals(MonitoringStatusResult.Failure(1, 3, true), failed.display)
        assertEquals(1, failed.state.consecutiveFailureCount)
        assertFalse(failed.shouldNotifyFailure)
        val recovered = f.handler.complete(MailCheckResult(true), 200L, 3, MailCheckTrigger.FCM, 0, true)
        assertEquals(MonitoringStatusResult.Success(null), recovered.display)
        assertEquals(0, recovered.state.consecutiveFailureCount)
    }
    private val failure = MailCheckResult(false, failureKind = MonitoringFailureKind.SERVER_UNAVAILABLE)
    private class Fixture {
        val status = MonitoringStatusRepository.forStore(MemoryPreferences())
        val handler = MailCheckCompletionHandler(status)
        suspend fun finish(result: MailCheckResult, threshold: Int = 3, at: Long = 100L,
            trigger: MailCheckTrigger = MailCheckTrigger.FCM) = handler.complete(result, at, threshold, trigger, 0)
    }

    @Test fun successResetsPersistedCountAndReturnsNormalAtCompletion() = runBlocking {
        val f = Fixture()
        f.finish(failure)
        val result = f.finish(MailCheckResult(true), at = 200L)
        assertEquals(0, result.state.consecutiveFailureCount)
        assertEquals(200L, result.state.lastSuccessfulCheckAtEpochMillis)
        assertEquals("NORMAL", result.display.observationState)
        assertFalse(result.shouldNotifyFailure)
        assertEquals(0, f.status.current().consecutiveFailureCount)
    }

    @Test fun failuresOneTwoThreeAndBeyondShareCountAndPreserveNotificationSuppression() = runBlocking {
        val f = Fixture()
        for (count in 1..5) {
            val result = f.finish(failure)
            assertEquals(count, result.state.consecutiveFailureCount)
            assertEquals(MonitoringStatusResult.Failure(count, 3), result.display)
            assertEquals(count == 3, result.shouldNotifyFailure)
            assertNotEquals("NORMAL", result.display.observationState)
        }
    }

    @Test fun recoveryAfterThresholdResetsCountAndRearmsExistingFailureNotification() = runBlocking {
        val f = Fixture()
        repeat(3) { f.finish(failure) }
        assertEquals("NORMAL", f.finish(MailCheckResult(true)).display.observationState)
        assertEquals(0, f.status.current().consecutiveFailureCount)
        assertFalse(f.finish(failure).shouldNotifyFailure)
        assertFalse(f.finish(failure).shouldNotifyFailure)
        assertTrue(f.finish(failure).shouldNotifyFailure)
    }

    @Test fun thresholdOneNotifiesOnFirstFailure() = runBlocking {
        val result = Fixture().finish(failure, threshold = 1)
        assertEquals(MonitoringStatusResult.Failure(1, 1), result.display)
        assertTrue(result.shouldNotifyFailure)
    }

    @Test fun thresholdNinetyNineIsUsedInsteadOfDefaultThree() = runBlocking {
        val f = Fixture()
        for (count in 1..100) {
            val result = f.finish(failure, threshold = 99)
            assertEquals(MonitoringStatusResult.Failure(count, 99), result.display)
            assertEquals(count == 99, result.shouldNotifyFailure)
        }
    }

    @Test fun fcmBackupManualAndStartupUseSameFinalResultAccounting() = runBlocking {
        for (trigger in MailCheckTrigger.entries) {
            val f = Fixture()
            assertEquals(MonitoringStatusResult.Failure(1, 3), f.finish(failure, trigger = trigger).display)
            assertEquals("NORMAL", f.finish(MailCheckResult(true), trigger = trigger).display.observationState)
            assertEquals(0, f.status.current().consecutiveFailureCount)
        }
    }

    @Test fun retryThenSuccessNeverPublishesIntermediateFailureOrIncrementsCount() = retryScenario(false)
    @Test fun exhaustedRetriesCountAsOneFinalFailure() = retryScenario(true)

    private fun retryScenario(exhaust: Boolean) = runBlocking {
        val f = Fixture()
        val displays = mutableListOf<MonitoringStatusResult>()
        var attempts = 0
        val receiver = object : MailReceiver {
            override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
                attempts++
                assertEquals(0, f.status.current().consecutiveFailureCount)
                assertTrue(displays.isEmpty())
                if (exhaust || attempts == 1) throw MailReceiverException(
                    MonitoringFailureKind.SERVER_UNAVAILABLE, SocketException())
            }
        }
        val source = object : DefaultNetworkSource {
            override val state = MutableStateFlow(DefaultNetworkState("fixture", true, true))
        }
        val network = NetworkStabilization(source, elapsedMillis = { 0L }, pause = {})
        val result = coordinator(NetworkStabilizingMailReceiver(receiver, network, false)).check()
        val completion = f.finish(result)
        displays += completion.display
        assertEquals(if (exhaust) 3 else 2, attempts)
        assertEquals(if (exhaust) 1 else 0, completion.state.consecutiveFailureCount)
        assertEquals(listOf(if (exhaust) "FAILURE" else "NORMAL"), displays.map { it.observationState })
        assertFalse(completion.shouldNotifyFailure)
    }

    @Test fun accountASuccessAndBFailureNeverPublishPartialNormal() = accountScenario(true)
    @Test fun allAccountsSuccessfulPublishOneNormal() = accountScenario(false)

    private fun accountScenario(failSecond: Boolean) = runBlocking {
        val f = Fixture()
        val displays = mutableListOf<MonitoringStatusResult>()
        val receiver = object : MailReceiver {
            override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
                consume(RemoteMail("A", "fixture-a", "", "", body = "", bodyFetched = true))
                assertTrue(displays.isEmpty())
                assertNull(f.status.current().lastSuccessfulCheckAtEpochMillis)
                if (failSecond) throw MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE)
                consume(RemoteMail("B", "fixture-b", "", "", body = "", bodyFetched = true))
            }
        }
        val result = coordinator(receiver).check()
        displays += f.finish(result).display
        assertEquals(listOf(if (failSecond) "FAILURE" else "NORMAL"), displays.map { it.observationState })
        assertEquals(if (failSecond) 1 else 0, f.status.current().consecutiveFailureCount)
    }

    private fun coordinator(receiver: MailReceiver) = MailCheckCoordinator(object : MonitoringMailStore {
        override suspend fun receivedMails() = emptyList<ReceivedMail>()
        override suspend fun saveReceived(mail: ReceivedMail) = true
        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {}
    }, object : MonitoringRuleSource { override suspend fun allRules() = emptyList<AlertRule>() }, receiver,
        object : MonitoringAlarmSink { override fun start(event: direct.kabu.alertkun.alarm.AlarmEvent) { fail("No rule matched") } })

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
