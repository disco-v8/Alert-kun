package direct.kabu.alertkun.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import direct.kabu.alertkun.ui.theme.AlertKunTheme
import org.junit.Rule
import org.junit.Test

/** Tests only the standalone setting; no real ViewModel/DataStore or Activity monitoring actions. */
class MonitoringHealthSettingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun checkboxCanDisableAndEnableHealthNotificationWhileMonitoringIsOn() {
        compose.setContent {
            var enabled by remember { mutableStateOf(true) }
            AlertKunTheme { MonitoringHealthSetting(enabled, monitoringEnabled = true) { enabled = it } }
        }
        compose.onNodeWithTag("monitoring_health_notification").assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("monitoring_health_notification").performClick().assertIsOn()
    }

    @Test fun monitoringOffDisablesCheckboxWithoutClearingDefaultSelection() {
        val monitoringEnabled = mutableStateOf(false)
        var changes = 0
        compose.setContent {
            AlertKunTheme { MonitoringHealthSetting(checked = true, monitoringEnabled = monitoringEnabled.value) { changes++ } }
        }
        compose.onNodeWithTag("monitoring_health_notification").assertIsOn().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, changes)
            monitoringEnabled.value = true
        }
        compose.onNodeWithTag("monitoring_health_notification").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, changes) }
    }
}
