package direct.kabu.alertkun.data.entity

import direct.kabu.alertkun.data.database.Converters
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.ReceivedMail
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersAndMappersTest {
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
