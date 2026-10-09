package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailCheckCoordinatorTest {
    @Test fun highestPriorityRuleProvidesAllAlarmSettingsBeforeProcessed() = runBlocking {
        val received = mail("alarm-settings", "ALERT").copy(from = "sender@example.invalid", receivedAtEpochMillis = 42L)
        var finalized = false
        val high = rule().copy(soundUri = "content://settings/system/alarm_alert", vibrationPattern = VibrationPattern.PATTERN_2,
            alarmMode = AlarmMode.UNTIL_STOPPED, backgroundColor = MailBackgroundColor.RED, soundType = AlarmSoundType.RINGTONE)
        val low = high.copy(id = "low", priority = 99, soundUri = "content://settings/system/notification_sound",
            vibrationPattern = VibrationPattern.PATTERN_3, alarmMode = AlarmMode.ONCE)
        val check = MailCheckCoordinator(
            object : MonitoringMailStore {
                override suspend fun receivedMails() = listOf(received)
                override suspend fun saveReceived(mail: ReceivedMail) = error("No new mail")
                override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
                    assertTrue(alertTriggered); assertEquals(high.id, matchedRuleId)
                    assertEquals(high.backgroundColor, backgroundColor); finalized = true
                }
            },
            object : MonitoringRuleSource { override suspend fun allRules() = listOf(low, high) },
            object : MailReceiver { override suspend fun fetchNewMessages() = emptyList<RemoteMail>(); override suspend fun fetchBody(remoteMail: RemoteMail): String? = null },
            object : MonitoringAlarmSink { override fun start(event: AlarmEvent) {
                assertFalse(finalized)
                assertEquals(high.soundUri, event.soundUri); assertEquals(high.vibrationPattern, event.vibrationPattern)
                assertEquals(high.alarmMode, event.alarmMode); assertEquals(high.id, event.ruleId)
                assertEquals(high.soundType, event.soundType)
                assertEquals(received.from, event.mailFrom); assertEquals(42L, event.receivedAtEpochMillis)
            } },
        )
        val result = check.check()
        assertTrue(result.success); assertTrue(finalized)
        assertEquals(high.id, result.selectedAlarm?.ruleId)
        assertEquals(received.subject, result.selectedAlarm?.mailSubject)
    }

    @Test fun handoffErrorDoesNotFinalizeReceivedMail() = runBlocking {
        var finalized = false
        val check = MailCheckCoordinator(
            object : MonitoringMailStore {
                override suspend fun receivedMails() = listOf(mail("failed-handoff", "ALERT"))
                override suspend fun saveReceived(mail: ReceivedMail) = false
                override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) { finalized = true }
            },
            object : MonitoringRuleSource { override suspend fun allRules() = listOf(rule()) },
            object : MailReceiver { override suspend fun fetchNewMessages() = emptyList<RemoteMail>(); override suspend fun fetchBody(remoteMail: RemoteMail): String? = null },
            object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { error("handoff rejected") } },
        )
        val result = check.check()
        assertFalse(result.success); assertFalse(finalized)
        assertEquals(null, result.selectedAlarm)
    }
    @Test fun selectedHighestPriorityColorIsSnapshottedAndLaterRulesDoNotRecolorOldMail() = runBlocking {
        val stored = mutableListOf<ReceivedMail>()
        val log = mutableListOf<String>()
        val remote = RemoteMail("account", "one", "ALERT", null)
        val red = rule().copy(backgroundColor = MailBackgroundColor.RED)
        val purple = red.copy(id = "lower", priority = 20, backgroundColor = MailBackgroundColor.PURPLE)
        assertTrue(coordinator(stored, log, listOf(remote), listOf(purple, red)).check().success)
        assertEquals(MailBackgroundColor.RED, stored.single().backgroundColor)
        assertEquals(red.id, stored.single().matchedRuleId)
        val changed = red.copy(backgroundColor = MailBackgroundColor.PURPLE, priority = 2)
        coordinator(stored, log, listOf(remote, remote.copy(remoteMessageId = "two")), listOf(changed)).check()
        assertEquals(listOf(MailBackgroundColor.RED, MailBackgroundColor.PURPLE), stored.map { it.backgroundColor })
        coordinator(stored, log, emptyList(), emptyList()).check()
        assertEquals(listOf(MailBackgroundColor.RED, MailBackgroundColor.PURPLE), stored.map { it.backgroundColor })
    }

    @Test fun unmatchedMailUsesDefaultColorEvenWithColoredRules() = runBlocking {
        val stored = mutableListOf<ReceivedMail>()
        coordinator(stored, mutableListOf(), listOf(RemoteMail("a", "new", "ordinary", null)),
            listOf(rule().copy(backgroundColor = MailBackgroundColor.RED))).check()
        assertEquals(MailBackgroundColor.DEFAULT, stored.single().backgroundColor)
        assertEquals(MailProcessingState.PROCESSED, stored.single().processingState)
    }

    @Test fun zeroRulesStillSavesHeaderOnlyMailAndFinalizesWithoutAnAlarm() = runBlocking {
        val stored = mutableListOf<ReceivedMail>()
        val log = mutableListOf<String>()
        val remote = RemoteMail("saved-account-id", "dovecot-uidl", "Subject", "sender@example.invalid",
            headers = mapOf("Message-ID" to "<test@example.invalid>"))
        val result = coordinator(stored, log, listOf(remote), emptyList()).check()
        assertTrue(result.success)
        assertEquals(1, result.receivedCount)
        assertEquals(0, result.alertCount)
        assertEquals(null, result.selectedAlarm)
        val saved = stored.single()
        assertEquals(remote.accountId, saved.accountId)
        assertEquals(remote.remoteMessageId, saved.remoteMessageId)
        assertEquals(remote.subject, saved.subject)
        assertEquals(remote.from, saved.from)
        assertEquals(remote.headers, saved.headers)
        assertEquals(1L, saved.receivedAtEpochMillis)
        assertEquals(MailProcessingState.PROCESSED, saved.processingState)
        assertFalse(saved.alertTriggered)
        assertEquals(null, saved.matchedRuleId)
        assertFalse(saved.bodyFetched)
        assertEquals(null, saved.body)
        assertEquals(0, log.count { it.startsWith("alarm:") })
    }

    @Test fun headerOnlyDuplicateDoesNotInsertAgainAndAccountIdsAreIndependent() = runBlocking {
        val stored = mutableListOf<ReceivedMail>()
        val log = mutableListOf<String>()
        val remote = RemoteMail("saved-account-id", "same-uidl", "Subject", null)
        val check = coordinator(stored, log, listOf(remote), emptyList())
        assertEquals(1, check.check().receivedCount)
        assertEquals(0, check.check().receivedCount)
        assertEquals(1, stored.size)
        val other = coordinator(stored, log, listOf(remote.copy(accountId = "other-account")), emptyList())
        assertEquals(1, other.check().receivedCount)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.processingState == MailProcessingState.PROCESSED })
    }
    @Test fun emptyMailboxIsASuccessfulCheck() = runBlocking {
        val result = coordinator(mutableListOf(), mutableListOf(), emptyList()).check()

        assertTrue(result.success)
        assertEquals(0, result.checkedCount)
        assertEquals(0, result.receivedCount)
    }

    @Test fun resumesReceivedBeforeFetchingThenAlarmsAndFinalizes() = runBlocking {
        val log = mutableListOf<String>()
        val stored = mutableListOf(mail("old", "ALERT old"))
        val coordinator = coordinator(stored, log, listOf(RemoteMail("test", "new", "ALERT new", null, body = "body")))
        val result = coordinator.check()
        assertTrue(result.success); assertEquals(1, result.alertCount)
        assertEquals("alarm:old", log.first())
        assertEquals(2, stored.count { it.processingState == MailProcessingState.PROCESSED })
    }

    @Test fun duplicateDoesNotAlarmAndBodyRequiredFetchesBeforeSaving() = runBlocking {
        val log = mutableListOf<String>()
        val stored = mutableListOf<ReceivedMail>()
        val bodyRule = rule(target = RuleTarget.BODY)
        val coordinator = coordinator(stored, log, listOf(RemoteMail("test", "same", "x", null, body = "ALERT")), listOf(bodyRule))
        val result = coordinator.check()
        assertTrue(result.success); assertEquals(1, result.alertCount)
        assertEquals(1, log.count { it.startsWith("alarm") })
        assertTrue(stored.single().bodyFetched)
        val repeat = coordinator.check()
        assertEquals(0, repeat.alertCount)
    }

    private fun coordinator(stored: MutableList<ReceivedMail>, log: MutableList<String>, remote: List<RemoteMail>, rules: List<AlertRule> = listOf(rule())) = MailCheckCoordinator(
        mailStore = object : MonitoringMailStore {
            override suspend fun receivedMails() = stored.filter { it.processingState == MailProcessingState.RECEIVED }
            override suspend fun saveReceived(mail: ReceivedMail): Boolean { check(mail.processingState == MailProcessingState.RECEIVED); if (stored.any { it.accountId == mail.accountId && it.remoteMessageId == mail.remoteMessageId }) return false; stored += mail; return true }
            override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) { val i = stored.indexOfFirst { it.id == id }; stored[i] = stored[i].copy(processingState = MailProcessingState.PROCESSED, alertTriggered = alertTriggered, matchedRuleId = matchedRuleId, backgroundColor = backgroundColor); log += "finalize:$id" }
        },
        ruleSource = object : MonitoringRuleSource { override suspend fun allRules() = rules },
        receiver = object : MailReceiver { override suspend fun fetchNewMessages() = remote.map { it.copy(body = null, bodyFetched = false) }; override suspend fun fetchBody(remoteMail: RemoteMail) = remote.first { it.remoteMessageId == remoteMail.remoteMessageId }.body },
        alarmSink = object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { log += "alarm:${event.mailId}" } },
        now = { 1L },
    )
    private fun rule(target: RuleTarget = RuleTarget.SUBJECT) = AlertRule("r", "Rule", "ALERT", target, 1, null, VibrationPattern.NONE, AlarmMode.ONCE, true)
    private fun mail(id: String, subject: String) = ReceivedMail(id, "a", id, 1, null, emptyMap(), subject, "body", true, MailProcessingState.RECEIVED, false, null)
}
