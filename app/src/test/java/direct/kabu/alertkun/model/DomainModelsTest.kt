package direct.kabu.alertkun.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DomainModelsTest {
    @Test
    fun mailProtocolDefinesAllSupportedProtocols() {
        assertEquals(
            listOf(
                MailProtocol.POP3,
                MailProtocol.POP3S,
                MailProtocol.IMAP4,
                MailProtocol.IMAPS,
            ),
            MailProtocol.entries,
        )
    }

    @Test
    fun receivedMailRepresentsUnprocessedMailWithoutFetchedBody() {
        val mail = ReceivedMail(
            id = "mail-1",
            accountId = "account-1",
            remoteMessageId = "uidl-1",
            receivedAtEpochMillis = 1_700_000_000_000,
            from = "monitor@example.com",
            headers = mapOf("Message-ID" to "<message-1@example.com>"),
            subject = "Service alert",
            body = null,
            bodyFetched = false,
            processingState = MailProcessingState.RECEIVED,
            alertTriggered = false,
            matchedRuleId = null,
        )

        assertEquals(MailProcessingState.RECEIVED, mail.processingState)
        assertFalse(mail.bodyFetched)
        assertFalse(mail.alertTriggered)
        assertNull(mail.matchedRuleId)
    }

    @Test
    fun alertRuleKeepsItsConfiguredTargetAndAlarmMode() {
        val rule = AlertRule(
            id = "rule-1",
            name = "Critical",
            searchText = "CRITICAL",
            target = RuleTarget.SUBJECT,
            priority = 1,
            soundUri = "content://media/internal/audio/media/1",
            vibrationPattern = VibrationPattern.PATTERN_1,
            alarmMode = AlarmMode.UNTIL_STOPPED,
            enabled = true,
        )

        assertEquals(RuleTarget.SUBJECT, rule.target)
        assertEquals(AlarmMode.UNTIL_STOPPED, rule.alarmMode)
        assertEquals(VibrationPattern.PATTERN_1, rule.vibrationPattern)
        assertEquals(1, rule.priority)
    }
}
