package direct.kabu.alertkun.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmState
import direct.kabu.alertkun.alarm.PendingAlarmStore
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.R
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlarmTestControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun settingsSeparatesTestingAndUsesShortLocalizedExplanationAndPatternDropdown() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun localized(locale: java.util.Locale) = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) })
            .getString(R.string.manual_alarm_note)
        assertEquals("以下はアラーム確認用です。", localized(java.util.Locale.JAPANESE))
        assertEquals("The following controls are for alarm testing.", localized(java.util.Locale.ENGLISH))
        composeRule.setContent { OtherSettingsScreen({}) }
        composeRule.onNodeWithTag("alarm_test_divider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("alarm_test_note").performScrollTo()
            .assertIsDisplayed().assertTextEquals(context.getString(R.string.manual_alarm_note))
        for (pattern in direct.kabu.alertkun.model.VibrationPattern.entries) {
            val item = "alarm_test_vibration_${pattern.name}"
            composeRule.onNodeWithTag(item).assertDoesNotExist()
            composeRule.onNodeWithTag("alarm_test_vibration").performScrollTo().performClick()
            composeRule.onNodeWithTag(item).assertIsDisplayed().performClick()
            composeRule.onNodeWithTag(item).assertDoesNotExist()
            composeRule.onNodeWithTag("alarm_test_vibration").assertTextEquals(context.getString(when (pattern) {
                direct.kabu.alertkun.model.VibrationPattern.NONE -> R.string.vibration_none
                direct.kabu.alertkun.model.VibrationPattern.PATTERN_1 -> R.string.vibration_pattern_1
                direct.kabu.alertkun.model.VibrationPattern.PATTERN_2 -> R.string.vibration_pattern_2
                direct.kabu.alertkun.model.VibrationPattern.PATTERN_3 -> R.string.vibration_pattern_3
            }))
        }
    }

    @Test fun developerCountSelectorOffersAllFourModes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val selected = androidx.compose.runtime.mutableStateOf(AlarmMode.ONCE)
        val controller = AlarmController.getInstance(context)
        try {
            controller.stop()
            composeRule.setContent {
                AlarmCountSelector(selected.value, "alarm_test_count") { selected.value = it }
                AlarmTestControls(onStart = {
                    controller.start(direct.kabu.alertkun.alarm.AlarmEvent("developer-count", "rule", "Rule", null,
                        null, direct.kabu.alertkun.model.VibrationPattern.NONE, selected.value))
                }, onStop = { controller.stop() })
            }
            for (mode in AlarmMode.entries) {
                composeRule.onNodeWithTag("alarm_test_count").performClick()
                composeRule.onNodeWithTag("alarm_test_count_${mode.name}").performClick()
                composeRule.runOnIdle { assertEquals(mode, selected.value) }
                composeRule.onNodeWithTag("alarm_test_start").performClick()
                composeRule.onNodeWithTag("alarm_test_stop").performClick()
            }
        } finally { controller.stop() }
    }

    @Test fun allManualAlarmActionsAreAvailable() {
        var startCount = 0
        var stopCount = 0
        composeRule.setContent {
            AlarmTestControls(
                onStart = { startCount++ },
                onStop = { stopCount++ },
            )
        }

        composeRule.onNodeWithTag("alarm_test_start").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("alarm_test_stop").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, startCount)
            assertEquals(1, stopCount)
        }
    }

    @Test fun developerSettingsCanSelectAndSendAllSoundTypesThroughTheRealController() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = AlarmController.getInstance(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        try {
            controller.stop()
            composeRule.setContent { OtherSettingsScreen(onBack = {}) }
            for (type in AlarmSoundType.entries) {
                composeRule.onNodeWithTag("alarm_sound_type").performScrollTo().performClick()
                composeRule.onNodeWithTag("alarm_sound_type_${type.name}").performClick()
                composeRule.onNodeWithTag("alarm_sound_type").assertTextEquals(context.getString(when (type) {
                    AlarmSoundType.ALARM -> R.string.alarm_sound_type_alarm
                    AlarmSoundType.NOTIFICATION -> R.string.alarm_sound_type_notification
                    AlarmSoundType.RINGTONE -> R.string.alarm_sound_type_ringtone
                }))
                composeRule.onNodeWithTag("alarm_test_count").performScrollTo().performClick()
                composeRule.onNodeWithTag("alarm_test_count_UNTIL_STOPPED").performClick()
                composeRule.onNodeWithTag("alarm_test_start").performScrollTo().performClick()
                composeRule.runOnIdle {
                    val event = PendingAlarmStore(context).load()!!.event
                    assertEquals(type, event.soundType)
                    assertNull(event.soundUri)
                    assertEquals(AlarmMode.UNTIL_STOPPED, event.alarmMode)
                    controller.stop()
                }
                composeRule.onNodeWithTag("alarm_test_count").performScrollTo().performClick()
                composeRule.onNodeWithTag("alarm_test_count_ONCE").performClick()
                composeRule.onNodeWithTag("alarm_test_start").performScrollTo().performClick()
                composeRule.runOnIdle {
                    assertEquals(AlarmState.IDLE, controller.currentState())
                    assertNull(PendingAlarmStore(context).load())
                    controller.stop()
                }
            }
        } finally { controller.stop() }
    }
}
