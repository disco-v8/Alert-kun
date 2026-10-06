package direct.kabu.alertkun.data.entity

import direct.kabu.alertkun.data.database.Converters
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.ReceivedMail
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersAndMappersTest {
    @Test fun allSevenAccountPoliciesRoundTripAndUnknownNamesRetainMail() {
        val converters = Converters()
        assertEquals(direct.kabu.alertkun.model.ServerDeletePolicy.NEVER, converters.toServerDeletePolicy("UNKNOWN"))
        val account = direct.kabu.alertkun.model.MailAccount("a", "A", direct.kabu.alertkun.model.MailProtocol.POP3S,
            "example.invalid", 995, "u", "key", false, null, true)
        direct.kabu.alertkun.model.ServerDeletePolicy.entries.forEach { policy ->
            assertEquals(policy, converters.toServerDeletePolicy(converters.fromServerDeletePolicy(policy)))
            val selected = account.copy(serverDeletePolicy = policy)
            assertEquals(selected, selected.toEntity().toDomain())
        }
    }
    @Test fun enumAndHeadersRoundTripAsStrings() {
        val converters = Converters()
        assertEquals("RECEIVED", converters.fromProcessingState(MailProcessingState.RECEIVED))
        assertEquals(mapOf("X-Alert" to "true"), converters.toHeaders(converters.fromHeaders(mapOf("X-Alert" to "true"))))
    }
    @Test fun receivedMailRoundTripsThroughEntity() {
        val mail = ReceivedMail("mail", "account", "uidl", 1L, "from", mapOf("Message-ID" to "id"), "subject", null, false, MailProcessingState.RECEIVED, false, null)
        assertEquals(mail, mail.toEntity().toDomain())
    }
}
