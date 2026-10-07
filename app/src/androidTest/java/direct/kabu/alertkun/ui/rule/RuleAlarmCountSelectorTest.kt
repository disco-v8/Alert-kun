package direct.kabu.alertkun.ui.rule

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.AlarmMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuleAlarmCountSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newRuleDefaultsToOnceAndDropdownUpdatesAllFourCounts() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = RuleEditViewModel(context)
        compose.setContent { RuleEditScreen(null, false, {}, {}, viewModel) }
        compose.onNodeWithTag("rule_alarm_count").performScrollTo().assertTextEquals(context.getString(R.string.alarm_once))
        for (mode in AlarmMode.entries) {
            compose.onNodeWithTag("rule_alarm_count").performClick()
            compose.onNodeWithTag("rule_alarm_count_${mode.name}").performClick()
            compose.runOnIdle { assertEquals(mode, viewModel.uiState.value.rule.alarmMode) }
            val resource = when (mode) {
                AlarmMode.ONCE -> R.string.alarm_once
                AlarmMode.THREE_TIMES -> R.string.alarm_three_times
                AlarmMode.FIVE_TIMES -> R.string.alarm_five_times
                AlarmMode.UNTIL_STOPPED -> R.string.alarm_until_stopped
            }
            compose.onNodeWithTag("rule_alarm_count").assertTextEquals(context.getString(resource))
        }
    }
}
