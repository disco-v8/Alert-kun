package direct.kabu.alertkun.ui.mail

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.MailBackgroundColor

data class MailCardColors(val background: Color, val content: Color)

/** DEFAULT matches Material3 Card; preset colors are presentation-only and never persisted. */
fun MailBackgroundColor.cardColors(scheme: ColorScheme): MailCardColors {
    if (this == MailBackgroundColor.DEFAULT) return MailCardColors(scheme.surfaceContainerHighest, scheme.onSurface)
    val darkTheme = scheme.background.luminance() < 0.5f
    val background = when (this) {
        MailBackgroundColor.DARK -> Color(0xFF30343B)
        MailBackgroundColor.PURPLE -> if (darkTheme) Color(0xFF44334F) else Color(0xFFEBDDF3)
        MailBackgroundColor.RED -> if (darkTheme) Color(0xFF523335) else Color(0xFFF3DEDF)
        MailBackgroundColor.ORANGE -> if (darkTheme) Color(0xFF503B2C) else Color(0xFFF4E2CE)
        MailBackgroundColor.YELLOW -> if (darkTheme) Color(0xFF49432B) else Color(0xFFF3EDCA)
        MailBackgroundColor.LIME -> if (darkTheme) Color(0xFF3B452C) else Color(0xFFE6EDCF)
        MailBackgroundColor.LIGHT_BLUE -> if (darkTheme) Color(0xFF2C424E) else Color(0xFFD9EBF4)
        MailBackgroundColor.DEFAULT -> error("Handled above")
    }
    return MailCardColors(background, if (darkTheme || this == MailBackgroundColor.DARK) Color(0xFFF5F5F5) else Color(0xFF202124))
}

@Composable fun MailBackgroundColor.cardColors() = cardColors(MaterialTheme.colorScheme)

@Composable fun MailBackgroundColor.label(): String = stringResource(when (this) {
    MailBackgroundColor.DEFAULT -> R.string.mail_color_default
    MailBackgroundColor.DARK -> R.string.mail_color_dark
    MailBackgroundColor.PURPLE -> R.string.mail_color_purple
    MailBackgroundColor.RED -> R.string.mail_color_red
    MailBackgroundColor.ORANGE -> R.string.mail_color_orange
    MailBackgroundColor.YELLOW -> R.string.mail_color_yellow
    MailBackgroundColor.LIME -> R.string.mail_color_lime
    MailBackgroundColor.LIGHT_BLUE -> R.string.mail_color_light_blue
})
