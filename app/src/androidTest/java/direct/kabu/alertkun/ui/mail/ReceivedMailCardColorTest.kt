package direct.kabu.alertkun.ui.mail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.ReceivedMail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReceivedMailCardColorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardUsesPersistedColorWithoutLookingUpAnyRule() {
        val scheme = lightColorScheme()
        val mail = ReceivedMail("snapshot", "a", "uid", 1, null, emptyMap(), "Subject", null, false,
            MailProcessingState.PROCESSED, true, "deleted-rule", MailBackgroundColor.RED)
        compose.setContent { MaterialTheme(colorScheme = scheme) { ReceivedMailCard(mail) } }
        val pixels = compose.onNodeWithTag("received_mail_snapshot").captureToImage().toPixelMap()
        assertEquals(MailBackgroundColor.RED.cardColors(scheme).background.toArgb(),
            pixels[pixels.width - 8, pixels.height / 2].toArgb())
    }
}
