package direct.kabu.alertkun.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
import android.app.ActivityManager
import android.content.Context
import direct.kabu.alertkun.alarm.AlarmPlaybackService
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.alarm.PendingAlarm
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlarmTestControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun settingsShowsStartupCheckboxAndVerificationToolsWithoutAlarmNote() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun localized(locale: java.util.Locale) = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) })
            .getString(R.string.developer_tools)
        assertEquals("確認ツール", localized(java.util.Locale.JAPANESE))
        assertEquals("Verification tools", localized(java.util.Locale.ENGLISH))
        composeRule.setContent { OtherSettingsScreen({}) }
        composeRule.onNodeWithTag("check_immediately_on_app_start").performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        composeRule.onNodeWithTag("monitoring_health_notification").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        composeRule.onNodeWithTag("developer_tools").performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.developer_tools))
        composeRule.onNodeWithTag("alarm_test_note").assertDoesNotExist()
    }

    @Test fun settingsSeparatesTestingAndUsesPatternDropdownWithoutAlarmNote() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        composeRule.setContent { OtherSettingsScreen({}) }
        composeRule.onNodeWithTag("alarm_test_divider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("alarm_test_note").assertDoesNotExist()
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
                androidx.compose.foundation.layout.Column {
                    AlarmCountSelector(selected.value, "alarm_test_count") { selected.value = it }
                    AlarmTestControls(onStart = {
                        controller.start(direct.kabu.alertkun.alarm.AlarmEvent("developer-count", "rule", "Rule", null,
                            null, direct.kabu.alertkun.model.VibrationPattern.NONE, selected.value))
                    }, onStop = { controller.stop() })
                }
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
                var token: String? = null
                composeRule.runOnIdle {
                    val handoff = checkNotNull(PendingAlarmStore(context).load())
                    token = handoff.token
                    assertEquals(type, handoff.event.soundType)
                    assertEquals(AlarmMode.ONCE, handoff.event.alarmMode)
                    assertNull(handoff.event.soundUri)
                    assertEquals(AlarmState.IDLE, controller.currentState())
                }
                assertFiniteServiceAndCleanup(context, controller, checkNotNull(token), 1)
            }
        } finally { controller.stop() }
    }

    @Test fun allFiniteModesPersistHandoffReachServiceAndCleanUpAfterCompletion() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = AlarmController.getInstance(context)
        val selected = androidx.compose.runtime.mutableStateOf(AlarmMode.ONCE)
        val sound = File.createTempFile("finite-controls-", ".wav", context.cacheDir)
        // Short PCM fixture makes completion checks independent of the device's default sound duration.
        val samples = 2_400
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 8_000) * 1_000).toInt().toShort()) }
        sound.writeBytes(bytes.array())
        var handoff: PendingAlarm? = null
        try {
            controller.stop()
            composeRule.setContent {
                androidx.compose.foundation.layout.Column {
                    AlarmCountSelector(selected.value, "finite_controls_count") { selected.value = it }
                    AlarmTestControls(onStart = {
                        controller.start(AlarmEvent("finite-controls", "fixture-rule", "Fixture", null,
                            sound.toURI().toString(), direct.kabu.alertkun.model.VibrationPattern.NONE, selected.value))
                        handoff = PendingAlarmStore(context).load()
                    }, onStop = { controller.stop() })
                }
            }
            for (mode in AlarmMode.entries.filter { it.finiteCount != null }) {
                composeRule.onNodeWithTag("finite_controls_count").performClick()
                composeRule.onNodeWithTag("finite_controls_count_${mode.name}").performClick()
                composeRule.onNodeWithTag("alarm_test_start").performClick()
                val saved = checkNotNull(handoff)
                assertEquals(mode, saved.event.alarmMode)
                assertEquals(sound.toURI().toString(), saved.event.soundUri)
                assertFiniteServiceAndCleanup(context, controller, saved.token, checkNotNull(mode.finiteCount))
            }
        } finally { controller.stop(); sound.delete() }
    }

    private fun assertFiniteServiceAndCleanup(context: Context, controller: AlarmController, token: String, count: Int) {
        // Durable activation evidence also handles a short sound completing before the next assertion.
        composeRule.waitUntil(10_000) {
            File(context.filesDir, "observability").listFiles().orEmpty().filter { it.isFile }.any { file ->
                file.useLines { lines -> lines.any { it.contains(" ALARM_FGS_STARTED ") && it.contains("alarmToken=$token") } }
            }
        }
        composeRule.waitUntil(90_000) {
            PendingAlarmStore(context).load() == null && !context.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE).any { it.service.className == AlarmPlaybackService::class.java.name }
        }
        composeRule.runOnIdle {
            assertEquals(AlarmState.IDLE, controller.currentState())
            assertNull(controller.session.value)
            synchronized(controller) {
                for (field in listOf("player", "focusRequest")) {
                    assertNull(AlarmController::class.java.getDeclaredField(field).apply { isAccessible = true }.get(controller))
                }
            }
        }
        val completedCycles = File(context.filesDir, "observability").listFiles().orEmpty().filter { it.isFile }
            .sumOf { file -> file.useLines { lines -> lines.count { it.contains(" ALARM_CYCLE_COMPLETED ") && it.contains("alarmToken=$token") } } }
        assertEquals(count, completedCycles)
        assertFalse(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .any { it.id == AlarmController.PLAYBACK_NOTIFICATION_ID })
    }
}
