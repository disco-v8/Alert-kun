package direct.kabu.alertkun.ui.rule

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuleListContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun ruleActionsExposeTheSelectedRule() {
        val rule = AlertRule("id-1", "Database failure", "failure", RuleTarget.SUBJECT, 1, null, VibrationPattern.NONE, AlarmMode.ONCE, true)
        var edited: String? = null
        var copied: String? = null
        var deleted: String? = null
        composeRule.setContent {
            RuleListContent(
                rules = listOf(rule),
                onEditRule = { edited = it },
                onCopyRule = { copied = it },
                onEnabledChanged = { _, _ -> },
                onDeleteRule = { deleted = it.id },
            )
        }

        composeRule.onNodeWithText("Database failure").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle {
            assertEquals("id-1", edited)
            assertEquals("id-1", copied)
            assertEquals("id-1", deleted)
        }
    }
}
