package direct.kabu.alertkun.ui.rule

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class RuleEnabledCheckboxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkboxReflectsEnabledAndUpdatesItInBothDirections() {
        val enabled = mutableStateOf(true)
        compose.setContent { RuleEnabledCheckbox(enabled.value) { enabled.value = it } }
        val checkbox = compose.onNodeWithTag("rule_enabled")
        checkbox.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        checkbox.assertIsOn().performClick().assertIsOff()
        checkbox.performClick().assertIsOn()
        compose.runOnIdle { enabled.value = false }
        checkbox.assertIsOff()
    }
}
