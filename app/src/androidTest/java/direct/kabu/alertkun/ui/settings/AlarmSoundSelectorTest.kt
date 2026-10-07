package direct.kabu.alertkun.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.alarm.AlarmSoundMapping
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AlarmSoundSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedSoundHasPickerAndTypeButtonsWithoutReset() {
        compose.setContent { AlarmSoundSelector("content://settings/system/alarm_alert", AlarmSoundType.ALARM, {}) {} }
        compose.onNodeWithTag("alarm_sound_selector").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test fun defaultSoundStillHasThePickerButton() {
        compose.setContent { AlarmSoundSelector(null, AlarmSoundType.ALARM, {}) {} }
        compose.onNodeWithTag("alarm_sound_selector").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test fun changingTypeClearsOldUriAndPickerReceivesTheSelectedCategory() {
        val state = mutableStateOf(AlertRule("r", "Rule", "WARN", RuleTarget.SUBJECT, 1,
            "content://sounds/old-alarm", VibrationPattern.NONE, AlarmMode.ONCE, true))
        val launches = mutableListOf<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != RingtoneManager.ACTION_RINGTONE_PICKER) return null
                launches.add(intent)
                val result = Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.parse("content://sounds/new-selection"))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, result)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent {
                AlarmSoundSelector(state.value.soundUri, state.value.soundType,
                    onSoundTypeSelected = { state.value = state.value.withSoundType(it) },
                    onSelected = { state.value = state.value.copy(soundUri = it) })
            }
            for (type in listOf(AlarmSoundType.NOTIFICATION, AlarmSoundType.RINGTONE, AlarmSoundType.ALARM)) {
                compose.onNodeWithTag("alarm_sound_type").performClick()
                compose.onNodeWithTag("alarm_sound_type_${type.name}").performClick()
                compose.runOnIdle {
                    assertEquals(type, state.value.soundType)
                    assertNull(state.value.soundUri)
                }
                compose.onNodeWithTag("alarm_sound_selector").performClick()
                compose.waitUntil(5_000) { state.value.soundUri != null }
                compose.runOnIdle {
                    val launched = launches.last()
                    assertEquals(AlarmSoundMapping.ringtoneType(type), launched.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
                    @Suppress("DEPRECATION")
                    assertEquals(AlarmSoundMapping.defaultUri(type), launched.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
                    assertEquals("content://sounds/new-selection", state.value.soundUri)
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
