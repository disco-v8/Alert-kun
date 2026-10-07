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
import direct.kabu.alertkun.model.VibrationPattern

/** Selection UI shared by Rule Settings and manual alarm testing; preview belongs to the caller. */
@Composable
internal fun VibrationPatternSelector(selected: VibrationPattern, tag: String, onSelected: (VibrationPattern) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.vibration))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) {
            Text(vibrationPatternLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VibrationPattern.entries.forEach { pattern ->
                DropdownMenuItem(text = { Text(vibrationPatternLabel(pattern)) },
                    modifier = Modifier.testTag("${tag}_${pattern.name}"),
                    onClick = { onSelected(pattern); expanded = false })
            }
        }
    }
}

@Composable
private fun vibrationPatternLabel(pattern: VibrationPattern) = stringResource(when (pattern) {
    VibrationPattern.NONE -> R.string.vibration_none
    VibrationPattern.PATTERN_1 -> R.string.vibration_pattern_1
    VibrationPattern.PATTERN_2 -> R.string.vibration_pattern_2
    VibrationPattern.PATTERN_3 -> R.string.vibration_pattern_3
})
