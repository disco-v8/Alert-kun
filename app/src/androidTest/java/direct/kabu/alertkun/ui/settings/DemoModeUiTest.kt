package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.MonitoringState
import direct.kabu.alertkun.ui.main.MainScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DemoModeUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun mainDistinguishesMissingAccountAndExplicitDemoWithoutChangingMonitoringState() {
        val demo = mutableStateOf(false)
        compose.setContent {
            MainScreen(MonitoringState(true, null, null, 0), 5, 0, {}, {}, {}, {},
                demoModeEnabled = demo.value, hasEnabledAccount = false)
        }
        compose.onNodeWithTag("monitoring_status").assertTextEquals(app.getString(R.string.no_configured_mail_account))
        compose.onNodeWithTag("demo_mode_active").assertDoesNotExist()
        compose.runOnIdle { demo.value = true }
        compose.onNodeWithTag("demo_mode_active").assertIsDisplayed()
    }

    @Test fun dummyToolsRequireExplicitDemoToggleAndExistingSettingsArePreserved() = runBlocking {
        val repository = SettingsRepository(app)
        val before = repository.settings.first()
        try {
            repository.updateDemoModeEnabled(false)
            compose.setContent { OtherSettingsScreen({}) }
            compose.onNodeWithTag("demo_mode_enabled").performScrollTo().assertIsOff()
            compose.onNodeWithTag("queue_dummy_matching_mail").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("demo_mode_enabled").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { repository.settings.first().demoModeEnabled } }
            compose.onNodeWithTag("queue_dummy_matching_mail").performScrollTo().assertIsEnabled()
            org.junit.Assert.assertEquals(before.copy(demoModeEnabled = true), repository.settings.first())
        } finally { repository.updateDemoModeEnabled(before.demoModeEnabled) }
    }
}
