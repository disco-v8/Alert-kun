package direct.kabu.alertkun.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import direct.kabu.alertkun.ui.theme.AlertKunTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MyDnsSettingsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun visibilityToggleOnlyRevealsNewInputAndResetsWhenCleared() {
        composeRule.setContent {
            var state by remember { mutableStateOf(MyDnsSettingsUiState(passwordSaved = true, loading = false)) }
            AlertKunTheme {
                MyDnsSettingsContent(state, {}, { state = state.copy(password = it) }, {})
            }
        }
        composeRule.onNodeWithTag("mydns_password_visibility").assertDoesNotExist()
        composeRule.onNodeWithTag("mydns_master_password").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("************")))
        composeRule.onNodeWithTag("mydns_master_password").performClick()
        composeRule.onNodeWithTag("mydns_master_password").performTextInput("replacement-test-password")
        assertMaskedInput("replacement-test-password")
        composeRule.onNodeWithTag("mydns_password_visibility").performClick()
        composeRule.onNodeWithTag("mydns_master_password").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("replacement-test-password")))
        composeRule.onNodeWithTag("mydns_password_visibility").performClick()
        assertMaskedInput("replacement-test-password")
        composeRule.onNodeWithTag("mydns_password_visibility").performClick()
        composeRule.onNodeWithTag("mydns_master_password").performTextClearance()
        composeRule.onNodeWithTag("mydns_password_visibility").assertDoesNotExist()
        composeRule.onNodeWithTag("mydns_master_password").performTextInput("another-test-password")
        assertMaskedInput("another-test-password")
    }

    @Test fun acceptsInputAndSubmitsOnlyNewPassword() {
        var submittedId = ""
        var submittedPassword = ""
        composeRule.setContent {
            var state by remember { mutableStateOf(MyDnsSettingsUiState(loading = false)) }
            AlertKunTheme {
                MyDnsSettingsContent(state, { state = state.copy(masterId = it) }, { state = state.copy(password = it) }, {
                    submittedId = state.masterId
                    submittedPassword = state.password
                    state = state.copy(password = "", passwordSaved = true, saved = true)
                })
            }
        }
        composeRule.onNodeWithTag("mydns_master_id").performTextInput("test-master")
        composeRule.onNodeWithTag("mydns_master_password").performTextInput("test-password")
        assertMaskedInput("test-password")
        composeRule.onNodeWithTag("mydns_save").performClick()
        composeRule.runOnIdle {
            assertEquals("test-master", submittedId)
            assertEquals("test-password", submittedPassword)
        }
        composeRule.onNodeWithTag("mydns_save_status").assertIsDisplayed()
        composeRule.onNodeWithTag("mydns_master_password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("************")))
    }

    @Test fun savedCredentialIsNeverPrefilled() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        fun localized(locale: java.util.Locale, id: Int) = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) }).getString(id)
        assertEquals("MyDNS.JPユーザー情報", localized(java.util.Locale.JAPANESE, direct.kabu.alertkun.R.string.mydns_settings))
        assertEquals("MyDNS.JP利用者向けの監視アプリです。ご利用のMasterIDとMasterPwdを設定してください。",
            localized(java.util.Locale.JAPANESE, direct.kabu.alertkun.R.string.mydns_settings_note))
        assertEquals("MyDNS.JP設定を保存", localized(java.util.Locale.JAPANESE, direct.kabu.alertkun.R.string.mydns_save_settings))
        assertEquals("MyDNS.JP user information", localized(java.util.Locale.ENGLISH, direct.kabu.alertkun.R.string.mydns_settings))
        assertEquals("This monitoring app is for MyDNS.JP users. Please set your MasterID and MasterPwd.",
            localized(java.util.Locale.ENGLISH, direct.kabu.alertkun.R.string.mydns_settings_note))
        assertEquals("Save MyDNS.JP settings", localized(java.util.Locale.ENGLISH, direct.kabu.alertkun.R.string.mydns_save_settings))
        var submittedPassword: String? = null
        var actualInput = ""
        composeRule.setContent {
            var state by remember { mutableStateOf(MyDnsSettingsUiState(masterId = "test-master", passwordSaved = true, loading = false)) }
            AlertKunTheme {
                MyDnsSettingsContent(state, {}, { actualInput = it; state = state.copy(password = it) }, {
                    submittedPassword = state.password
                })
            }
        }
        composeRule.onNodeWithTag("mydns_settings_title").assertTextEquals(context.getString(direct.kabu.alertkun.R.string.mydns_settings))
        composeRule.onNodeWithTag("mydns_settings_note").assertTextEquals(context.getString(direct.kabu.alertkun.R.string.mydns_settings_note))
        composeRule.onNodeWithTag("mydns_save").assertTextEquals(context.getString(direct.kabu.alertkun.R.string.mydns_save_settings))
        composeRule.onNodeWithTag("mydns_master_id").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("test-master")))
        composeRule.onNodeWithTag("mydns_master_password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("************")))
        composeRule.onNodeWithTag("mydns_password_visibility").assertDoesNotExist()
        composeRule.onNodeWithTag("mydns_password_status", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("mydns_password_change_hint", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("mydns_save").performClick()
        composeRule.runOnIdle { assertEquals("", submittedPassword) }
        composeRule.onNodeWithTag("mydns_master_password").performClick()
        composeRule.onNodeWithTag("mydns_master_password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        composeRule.runOnIdle { assertEquals("", actualInput) }
        composeRule.onNodeWithTag("mydns_master_password").performTextInput("replacement-test-password")
        composeRule.onNodeWithTag("mydns_password_visibility").performClick()
        composeRule.onNodeWithTag("mydns_save").performClick()
        composeRule.runOnIdle { assertEquals("replacement-test-password", submittedPassword) }
    }

    private fun assertMaskedInput(input: String) {
        composeRule.onNodeWithTag("mydns_master_password").assert(SemanticsMatcher("new input is visually masked") { node ->
            val displayed = node.config[SemanticsProperties.EditableText].text
            displayed.length == input.length && displayed != input
        })
    }
}
