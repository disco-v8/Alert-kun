package direct.kabu.alertkun.ui.rule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.ui.mail.cardColors
import direct.kabu.alertkun.ui.mail.label
import java.util.Locale

@Composable
fun MailColorSample(color: MailBackgroundColor) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(20.dp).background(color.cardColors().background)
            .border(1.dp, MaterialTheme.colorScheme.outline))
        Text(color.label())
    }
}

@Composable
fun RuleBackgroundColorSelector(selected: MailBackgroundColor, onSelected: (MailBackgroundColor) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.rule_mail_background_color))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("rule_background_color_selector")) {
            MailColorSample(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MailBackgroundColor.entries.forEach { color ->
                DropdownMenuItem(
                    text = { MailColorSample(color) },
                    onClick = { onSelected(color); expanded = false },
                    modifier = Modifier.testTag("rule_background_color_${color.name.lowercase(Locale.ROOT)}"),
                )
            }
        }
    }
}
