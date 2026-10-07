package direct.kabu.alertkun.alarm

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.model.VibrationPattern
import direct.kabu.alertkun.ui.rule.RuleEditScreen
import direct.kabu.alertkun.ui.settings.OtherSettingsScreen
import direct.kabu.alertkun.ui.settings.rememberVibrationPreview
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VibrationPreviewInstrumentationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = AlarmController.getInstance(context)
    private val preview = VibrationPreview(context)

    @Before fun prepare() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        assumeTrue(context.getSystemService(VibratorManager::class.java).defaultVibrator.hasVibrator())
        controller.stop()
    }

    @After fun cleanup() {
        preview.cancel()
        controller.stop()
    }

    @Test fun eachPatternPlaysOneSharedCycleAndNoneDoesNotStartVibration() {
        val before = history()
        preview.select(VibrationPattern.NONE)
        assertEquals(before, history())
        for (pattern in VibrationPattern.entries.filter { it != VibrationPattern.NONE }) {
            preview.select(pattern)
            SystemClock.sleep(AlarmVibration.timings(pattern)!!.sum() + 500)
            val record = history().last()
            assertTrue(record.contains("finished"))
            assertFalse(record.contains("repeat="))
            val original = record.substringAfter("original:")
            val durations = Regex("Step=(\\d+)ms").findAll(original).map { it.groupValues[1].toLong() }.toList().toLongArray()
            assertArrayEquals(AlarmVibration.timings(pattern), durations)
            assertFalse(current().contains(context.packageName))
            assertEquals(AlarmState.IDLE, controller.currentState())
            assertNull(controller.pendingToken())
            Log.i("VibrationPreviewTest", "$pattern: ${record.trim()}")
        }
    }

    @Test fun rapidChangesCancelPreviousPreviewAndNoneCancelsImmediately() {
        val before = history().lastOrNull()
        preview.select(VibrationPattern.PATTERN_3)
        await { current().contains(context.packageName) }
        preview.select(VibrationPattern.PATTERN_2)
        await { history().lastOrNull() != before }
        assertTrue(history().last().contains("cancelled_by_user"))
        preview.select(VibrationPattern.PATTERN_3)
        await { current().contains(context.packageName) }
        preview.select(VibrationPattern.NONE)
        await { !current().contains(context.packageName) }
        assertTrue(history().last().contains("cancelled_by_user"))
    }

    @Test fun ruleSelectionPreviewsAndScreenDisposalCancelsWithoutSaving() {
        val visible = mutableStateOf(true)
        val before = history().lastOrNull()
        compose.setContent { if (visible.value) RuleEditScreen(null, false, {}, {}) }
        assertEquals(before, history().lastOrNull())
        compose.onNodeWithTag("rule_vibration").performScrollTo().performClick()
        compose.onNodeWithTag("rule_vibration_PATTERN_3").assertIsDisplayed()
        selectAndLeave("rule_vibration_PATTERN_3", visible)
        await { history().lastOrNull() != before }
        assertTrue(history().last().contains("cancelled_by_user"))
        assertFalse(current().contains(context.packageName))
        assertEquals(AlarmState.IDLE, controller.currentState())
        assertNull(controller.pendingToken())
    }

    @Test fun settingsSelectionPreviewsAndScreenDisposalCancels() {
        val visible = mutableStateOf(true)
        val before = history().lastOrNull()
        compose.setContent { if (visible.value) OtherSettingsScreen({}) }
        assertEquals(before, history().lastOrNull())
        compose.onNodeWithTag("alarm_test_vibration").performScrollTo().performClick()
        selectAndLeave("alarm_test_vibration_PATTERN_3", visible)
        await { history().lastOrNull() != before }
        assertTrue(history().last().contains("cancelled_by_user"))
        assertFalse(current().contains(context.packageName))
    }

    @Test fun backgroundLifecycleStopCancelsPreview() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lateinit var screenPreview: VibrationPreview
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                screenPreview = rememberVibrationPreview()
            }
        }
        compose.runOnIdle { screenPreview.select(VibrationPattern.PATTERN_3) }
        await { current().contains(context.packageName) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        await { !current().contains(context.packageName) }
        assertTrue(history().last().contains("cancelled_by_user"))
    }

    @Test fun previewCleanupDoesNotCancelAnAlarmThatReplacedIt() {
        preview.select(VibrationPattern.PATTERN_3)
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        AndroidVibrationOutput.play(vibrator, AlarmVibration.repeatingTimings(VibrationPattern.PATTERN_1)!!, 0, Any())
        await { current().contains("repeat=0") }
        preview.cancel()
        SystemClock.sleep(1_500)
        assertTrue(current().contains(context.packageName))
        assertTrue(current().contains("repeat=0"))
    }

    private fun current() = dump().substringAfter("CurrentVibration:").substringBefore("NextVibration:")
    private fun selectAndLeave(tag: String, visible: androidx.compose.runtime.MutableState<Boolean>) {
        // Dispatch the real selection callback without waiting through UI exit animations,
        // which can outlast a short preview on a cold device.
        val click = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { click() }
        await { current().contains(context.packageName) }
        compose.runOnUiThread { visible.value = false }
        compose.waitForIdle()
    }
    private fun history() = dump().lineSequence().filter { it.contains("| ${context.packageName} (") }
        // Android 17 also dumps aggregated history after recent records; it is not chronological.
        .distinct().sortedBy { it.trim().take(18) }.toList()
    private fun dump(): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("dumpsys vibrator_manager").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
        }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Vibration condition was not satisfied", condition())
    }
}
