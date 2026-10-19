package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PollAlarmSelectionTest {
    private val high = AlertRule("high", "High", "ALERT", RuleTarget.SUBJECT, 10, "content://high",
        VibrationPattern.PATTERN_3, AlarmMode.FIVE_TIMES, true, MailBackgroundColor.RED, AlarmSoundType.RINGTONE)
    private val low = high.copy(id = "low", name = "Low", searchText = "WARN", priority = 20,
        soundUri = "content://low", vibrationPattern = VibrationPattern.PATTERN_1, alarmMode = AlarmMode.UNTIL_STOPPED,
        backgroundColor = MailBackgroundColor.YELLOW, soundType = AlarmSoundType.NOTIFICATION)
    private fun mail(uid: String, subject: String, at: Long = 10, account: String = "a") =
        ReceivedMail("local-$account-$uid", account, uid, at, null, emptyMap(), subject, null, false,
            MailProcessingState.RECEIVED, false, null)

    private class Store(initial: List<ReceivedMail>) : MonitoringMailStore {
        val rows = initial.toMutableList()
        var batches = 0
        override suspend fun receivedMails() = rows.filter { it.processingState == MailProcessingState.RECEIVED }
        override suspend fun saveReceived(mail: ReceivedMail): Boolean {
            if (rows.any { it.accountId == mail.accountId && it.remoteMessageId == mail.remoteMessageId }) return false
            rows += mail; return true
        }
        override suspend fun finalizeBatch(outcomes: List<MailProcessingOutcome>) {
            batches++; super.finalizeBatch(outcomes)
        }
        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
            val index = rows.indexOfFirst { it.id == id }
            rows[index] = rows[index].copy(processingState = MailProcessingState.PROCESSED, alertTriggered = alertTriggered,
                matchedRuleId = matchedRuleId, backgroundColor = backgroundColor)
        }
    }

    private fun check(store: Store, events: MutableList<AlarmEvent>, rules: List<AlertRule> = listOf(low, high),
        remote: List<RemoteMail> = emptyList(), failHandoff: Boolean = false, deleted: () -> Unit = {}) = MailCheckCoordinator(
        store, object : MonitoringRuleSource { override suspend fun allRules() = rules },
        object : MailReceiver {
            override suspend fun fetchNewMessages() = remote
            override suspend fun fetchBody(remoteMail: RemoteMail): String? = error("Subject only")
            override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
                assertTrue(store.rows.all { it.processingState == MailProcessingState.PROCESSED }); deleted()
            }
        }, object : MonitoringAlarmSink {
            override fun start(event: AlarmEvent) {
                assertTrue(store.rows.filter { it.processingState == MailProcessingState.RECEIVED }.isNotEmpty())
                if (failHandoff) error("Handoff failed")
                events += event
            }
        }, now = { 100L },
    )

    @Test fun bothMailboxOrdersChooseOnlyHighAndSaveEachOwnRuleSnapshots() = runBlocking {
        for (rows in listOf(listOf(mail("alert", "ALERT", 1), mail("warn", "WARN", 99)),
            listOf(mail("warn", "WARN", 99), mail("alert", "ALERT", 1)))) {
            val store = Store(rows); val events = mutableListOf<AlarmEvent>()
            val result = check(store, events).check()
            assertTrue(result.success); assertEquals(1, result.alertCount); assertEquals(1, events.size)
            assertEquals(high.id, events.single().ruleId)
            assertEquals(high.soundType, events.single().soundType); assertEquals(high.soundUri, events.single().soundUri)
            assertEquals(high.vibrationPattern, events.single().vibrationPattern); assertEquals(high.alarmMode, events.single().alarmMode)
            val alert = store.rows.first { it.remoteMessageId == "alert" }; val warn = store.rows.first { it.remoteMessageId == "warn" }
            assertTrue(store.rows.all { it.processingState == MailProcessingState.PROCESSED })
            assertTrue(alert.alertTriggered); assertFalse(warn.alertTriggered)
            assertEquals(high.id, alert.matchedRuleId); assertEquals(low.id, warn.matchedRuleId)
            assertEquals(high.backgroundColor, alert.backgroundColor); assertEquals(low.backgroundColor, warn.backgroundColor)
            assertEquals(1, store.batches)
        }
    }

    @Test fun newMailOrdersAlsoChooseHighAndDuplicateDoesNotRealert() = runBlocking {
        val alert = RemoteMail("a", "alert", "ALERT", null); val warn = RemoteMail("a", "warn", "WARN", null)
        for (remote in listOf(listOf(alert, warn, alert), listOf(warn, alert, warn))) {
            val store = Store(emptyList()); val events = mutableListOf<AlarmEvent>()
            assertTrue(check(store, events, remote = remote).check().success)
            assertEquals(high.id, events.single().ruleId); assertEquals(2, store.rows.size)
            assertEquals(0, check(store, events, remote = remote).check().alertCount)
            assertEquals(1, events.size)
        }
    }

    @Test fun samePriorityUsesNewestThenStableIdentityIndependentOfListOrder() = runBlocking {
        val same = low.copy(priority = high.priority)
        for (rows in listOf(listOf(mail("alert", "ALERT", 1), mail("warn", "WARN", 2)),
            listOf(mail("warn", "WARN", 2), mail("alert", "ALERT", 1)))) {
            val events = mutableListOf<AlarmEvent>()
            check(Store(rows), events, listOf(high, same)).check()
            assertEquals(same.id, events.single().ruleId)
        }
        val tied = listOf(mail("z", "ALERT"), mail("a", "WARN"))
        for (rows in listOf(tied, tied.reversed())) {
            val events = mutableListOf<AlarmEvent>()
            check(Store(rows), events, listOf(high, same)).check()
            assertEquals("local-a-a", events.single().mailId)
        }
    }

    @Test fun candidatesSpanAllAccountsAndRecoveryPlusNewMailInOneCheck() = runBlocking {
        val store = Store(listOf(mail("warn", "WARN", account = "first-account")))
        val events = mutableListOf<AlarmEvent>()
        check(store, events, remote = listOf(RemoteMail("second-account", "alert", "ALERT", null))).check()
        assertEquals(high.id, events.single().ruleId)
        assertFalse(store.rows.first().alertTriggered)
        assertTrue(store.rows.last().alertTriggered)
    }

    @Test fun eachMailStillSelectsItsHighestRuleAndNoCandidateMeansNoHandoff() = runBlocking {
        val events = mutableListOf<AlarmEvent>()
        check(Store(listOf(mail("both", "ALERT WARN"))), events).check()
        assertEquals(high.id, events.single().ruleId)
        val unmatched = Store(listOf(mail("none", "ordinary")))
        assertEquals(0, check(unmatched, mutableListOf()).check().alertCount)
        assertFalse(unmatched.rows.single().alertTriggered); assertNull(unmatched.rows.single().matchedRuleId)
        assertEquals(MailBackgroundColor.DEFAULT, unmatched.rows.single().backgroundColor)
    }

    @Test fun completeBodyHigherPriorityBeatsLowerSubjectRule() = runBlocking {
        val store=Store(emptyList());val events=mutableListOf<AlarmEvent>()
        val body=high.copy(target=RuleTarget.BODY)
        val subject=low.copy(target=RuleTarget.SUBJECT)
        assertTrue(check(store,events,listOf(subject,body),remote=listOf(RemoteMail("a","both","WARN",null,
            body="ALERT",bodyFetched=true))).check().success)
        assertEquals(body.id,events.single().ruleId)
        assertEquals(body.id,store.rows.single().matchedRuleId)
    }

    @Test fun failedWinnerDoesNotFallbackFinalizeOrDeleteAndRetrySelectsSameWinner() = runBlocking {
        val store = Store(listOf(mail("alert", "ALERT"), mail("warn", "WARN")))
        val events = mutableListOf<AlarmEvent>(); var deletes = 0
        assertFalse(check(store, events, failHandoff = true, deleted = { deletes++ }).check().success)
        assertTrue(events.isEmpty()); assertEquals(0, store.batches); assertEquals(0, deletes)
        assertTrue(store.rows.all { it.processingState == MailProcessingState.RECEIVED && !it.alertTriggered })
        assertTrue(check(store, events, deleted = { deletes++ }).check().success)
        assertEquals(high.id, events.single().ruleId); assertEquals(1, deletes)
    }

    @Test fun laterPollCanHandoffNewCandidateAndReplacePriorWinner() = runBlocking {
        val store = Store(emptyList()); val events = mutableListOf<AlarmEvent>()
        val old = RemoteMail("a", "old", "ALERT", null); val fresh = RemoteMail("a", "new", "WARN", null)
        check(store, events, remote = listOf(old)).check()
        check(store, events, remote = listOf(old, fresh)).check()
        assertEquals(listOf(high.id, low.id), events.map { it.ruleId })
        assertEquals(2, events.size)
    }

    @Test fun remoteFailureAfterRecoveryEvaluationDoesNotHandoffOrFinalizeEarly() = runBlocking {
        val store = Store(listOf(mail("pending", "ALERT")))
        var handoffs = 0
        val coordinator = MailCheckCoordinator(store,
            object : MonitoringRuleSource { override suspend fun allRules() = listOf(high) },
            object : MailReceiver {
                override suspend fun fetchNewMessages(): List<RemoteMail> = error("Remote fetch failed")
                override suspend fun fetchBody(remoteMail: RemoteMail): String? = null
                override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) = error("Must not delete")
            }, object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { handoffs++ } })
        assertFalse(coordinator.check().success)
        assertEquals(0, handoffs); assertEquals(0, store.batches)
        assertEquals(MailProcessingState.RECEIVED, store.rows.single().processingState)
    }
}
