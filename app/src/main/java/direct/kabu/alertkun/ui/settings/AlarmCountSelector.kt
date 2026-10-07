package direct.kabu.alertkun.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.AlarmMode

@Composable
internal fun alarmCountLabel(mode: AlarmMode): String = stringResource(when (mode) {
    AlarmMode.ONCE -> R.string.alarm_once
    AlarmMode.THREE_TIMES -> R.string.alarm_three_times
    AlarmMode.FIVE_TIMES -> R.string.alarm_five_times
    AlarmMode.UNTIL_STOPPED -> R.string.alarm_until_stopped
})

@Composable
internal fun AlarmCountSelector(selected: AlarmMode, tag: String, onSelected: (AlarmMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.alarm_count))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) {
            Text(alarmCountLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AlarmMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(alarmCountLabel(mode)) },
                    modifier = Modifier.testTag("${tag}_${mode.name}"),
                    onClick = { onSelected(mode); expanded = false })
            }
        }
    }
}
