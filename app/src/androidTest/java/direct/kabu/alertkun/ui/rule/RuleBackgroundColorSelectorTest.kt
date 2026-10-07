package direct.kabu.alertkun.ui.rule

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import direct.kabu.alertkun.model.MailBackgroundColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class RuleBackgroundColorSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allPresetsCanBeSelectedAndRestored() {
        val selected = mutableStateOf(MailBackgroundColor.DEFAULT)
        compose.setContent { RuleBackgroundColorSelector(selected.value) { selected.value = it } }
        MailBackgroundColor.entries.forEach { color ->
            compose.onNodeWithTag("rule_background_color_selector").performClick()
            compose.onNodeWithTag("rule_background_color_${color.name.lowercase(Locale.ROOT)}").performClick()
            compose.runOnIdle { assertEquals(color, selected.value) }
        }
    }
}
