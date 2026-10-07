package direct.kabu.alertkun.data

import direct.kabu.alertkun.data.database.Converters
import direct.kabu.alertkun.data.entity.toDomain
import direct.kabu.alertkun.data.entity.toEntity
import direct.kabu.alertkun.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MailBackgroundColorPersistenceTest {
    private val rule = AlertRule("r", "Rule", "WARN", RuleTarget.SUBJECT, 1, null,
        VibrationPattern.NONE, AlarmMode.ONCE, true)

    @Test fun newAndExistingModelsDefaultToDefault() {
        assertEquals(MailBackgroundColor.DEFAULT, rule.backgroundColor)
        assertEquals(MailBackgroundColor.DEFAULT, rule.toEntity().toDomain().backgroundColor)
        val mail = ReceivedMail("m", "a", "uid", 1, null, emptyMap(), "subject", null, false,
            MailProcessingState.RECEIVED, false, null)
        assertEquals(MailBackgroundColor.DEFAULT, mail.toEntity().toDomain().backgroundColor)
    }

    @Test fun allColorsSurviveMappingStorageConversionAndRuleCopy() {
        val converters = Converters()
        MailBackgroundColor.entries.forEach { color ->
            val original = rule.copy(backgroundColor = color)
            assertEquals(color, original.toEntity().toDomain().backgroundColor)
            assertEquals(color, original.copy(id = "copy", name = "Copy").backgroundColor)
            assertEquals(color, converters.toBackgroundColor(converters.fromBackgroundColor(color)))
            val mail = ReceivedMail("m", "a", "uid", 1, null, emptyMap(), "subject", null, false,
                MailProcessingState.PROCESSED, true, original.id, color)
            assertEquals(color, mail.toEntity().toDomain().backgroundColor)
        }
    }
}
