package direct.kabu.alertkun.ui.settings

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmSoundMapping
import direct.kabu.alertkun.model.AlarmSoundType

/** Uses existing soundUri persistence and the system picker, not a new preset/channel schema. */
@Composable
internal fun AlarmSoundSelector(
    soundUri: String?,
    soundType: AlarmSoundType,
    onSoundTypeSelected: (AlarmSoundType) -> Unit,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pickerType by rememberSaveable { mutableStateOf<AlarmSoundType?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pickerType == soundType) {
            val data = result.data
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) onSelected(uri.toString())
        }
        pickerType = null
    }
    Column {
        Text(stringResource(R.string.alarm_sound_type))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("alarm_sound_type")) {
            Text(stringResource(soundType.labelResource()))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AlarmSoundType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(type.labelResource())) },
                    onClick = { onSoundTypeSelected(type); expanded = false },
                    modifier = Modifier.testTag("alarm_sound_type_${type.name}"),
                )
            }
        }
        Text(stringResource(R.string.alarm_sound))
        OutlinedButton(onClick = {
            pickerType = soundType
            picker.launch(AlarmSoundMapping.pickerIntent(soundType, soundUri))
        }, modifier = Modifier.testTag("alarm_sound_selector")) {
            Text(stringResource(if (soundUri == null) soundType.defaultLabelResource() else R.string.alarm_sound_selected))
        }
    }
}

private fun AlarmSoundType.labelResource(): Int = when (this) {
    AlarmSoundType.ALARM -> R.string.alarm_sound_type_alarm
    AlarmSoundType.NOTIFICATION -> R.string.alarm_sound_type_notification
    AlarmSoundType.RINGTONE -> R.string.alarm_sound_type_ringtone
}

private fun AlarmSoundType.defaultLabelResource(): Int = when (this) {
    AlarmSoundType.ALARM -> R.string.alarm_sound_default
    AlarmSoundType.NOTIFICATION -> R.string.alarm_sound_default_notification
    AlarmSoundType.RINGTONE -> R.string.alarm_sound_default_ringtone
}
