package direct.kabu.alertkun.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AlarmTestControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun allManualAlarmActionsAreAvailable() {
        var onceCount = 0
        var untilStoppedCount = 0
        var stopCount = 0
        composeRule.setContent {
            AlarmTestControls(
                onOnce = { onceCount++ },
                onUntilStopped = { untilStoppedCount++ },
                onStop = { stopCount++ },
            )
        }

        composeRule.onNodeWithTag("alarm_test_once").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("alarm_test_until_stopped").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("alarm_test_stop").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, onceCount)
            assertEquals(1, untilStoppedCount)
            assertEquals(1, stopCount)
        }
    }
}
