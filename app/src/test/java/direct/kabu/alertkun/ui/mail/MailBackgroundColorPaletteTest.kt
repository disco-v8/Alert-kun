package direct.kabu.alertkun.ui.mail

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import direct.kabu.alertkun.model.MailBackgroundColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailBackgroundColorPaletteTest {
    @Test fun defaultUsesOriginalCardColors() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            val colors = MailBackgroundColor.DEFAULT.cardColors(scheme)
            assertEquals(scheme.surfaceContainerHighest, colors.background)
            assertEquals(scheme.onSurface, colors.content)
        }
    }

    @Test fun allPresetsHaveReadableTextInBothThemes() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            MailBackgroundColor.entries.forEach { preset ->
                val colors = preset.cardColors(scheme)
                val luminances = listOf(colors.background.luminance(), colors.content.luminance())
                val contrast = (luminances.max() + 0.05f) / (luminances.min() + 0.05f)
                assertTrue("$preset contrast $contrast", contrast >= 4.5f)
            }
            assertTrue(MailBackgroundColor.DARK.cardColors(scheme).content.luminance() > 0.8f)
        }
    }
}
