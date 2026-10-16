package direct.kabu.alertkun.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.ui.account.ServerDeletePolicySelector
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalMailSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameSevenOptionsAreSelectableIndependentlyOfAccountSettings() {
        val selected = mutableStateOf(ServerDeletePolicy.AFTER_1_WEEK)
        compose.setContent {
            ServerDeletePolicySelector(selected.value, true, R.string.local_mail_delete_policy,
                "local_mail_delete_policy", false) { selected.value = it }
        }
        ServerDeletePolicy.entries.forEach { policy ->
            compose.onNodeWithTag("local_mail_delete_policy").performClick()
            compose.onNodeWithTag("local_mail_delete_policy_${policy.name}").performClick()
            compose.runOnIdle { assertEquals(policy, selected.value) }
        }
    }

    @Test fun appScreenNotificationAndDeletionLabelsAreLocalizedConsistently() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(Locale.ENGLISH to "Alert-kun", Locale.JAPANESE to "アラートくん").forEach { (locale, name) ->
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            listOf(R.string.app_name, R.string.main_title, R.string.monitoring_status_title).forEach {
                assertEquals(name, localized.getString(it))
            }
            assertTrue(localized.getString(R.string.alarm_notification_title, "fixture").startsWith(name))
            assertFalse(localized.getString(R.string.alarm_channel_name).contains("AlertKun"))
        }
        val ja = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.JAPANESE) })
        assertEquals("内部からメール削除", ja.getString(R.string.local_mail_delete_policy))
        assertEquals("サーバーからメール削除", ja.getString(R.string.server_delete_policy))
    }
}
