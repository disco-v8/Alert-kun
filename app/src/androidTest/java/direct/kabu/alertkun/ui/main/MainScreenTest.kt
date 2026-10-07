package direct.kabu.alertkun.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import direct.kabu.alertkun.model.MonitoringState
import direct.kabu.alertkun.ui.theme.AlertKunTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.Locale

class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun japaneseNextScheduleResourcesUseTheShorterLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val japanese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        })
        assertEquals("次回予定：2026/10/06 12:00頃", japanese.getString(R.string.next_scheduled_check, "2026/10/06 12:00"))
        assertEquals("次回予定：未定", japanese.getString(R.string.next_check_pending))
        assertEquals("次回予定：なし", japanese.getString(R.string.next_check_none))
    }

    @Test fun activeMonitoringShowsTheConfiguredInterval() {
        setMainContent(MonitoringState(true, null, null, 0), 5)

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_status").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.monitoring_active_with_interval, 5))
    }

    @Test fun stoppedMonitoringDoesNotShowThePollingInterval() {
        setMainContent(MonitoringState(false, null, null, 0), 5)

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_status").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.monitoring_stopped))
        composeRule.onNodeWithTag("monitoring_last_check").assertIsDisplayed()
    }

    @Test fun activeMonitoringWithNoDateShowsPendingWithoutApproximation() {
        setMainContent(MonitoringState(true, null, null, 0), 5)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_next_check").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.next_check_pending))
    }

    @Test fun activeMonitoringWithDateShowsApproximateFormattedDate() {
        val timestamp = 1_700_000_000_000L
        setMainContent(MonitoringState(true, null, timestamp, 0), 5)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_next_check").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.next_scheduled_check, formatMonitoringTimestamp(timestamp)))
    }

    @Test fun stoppedMonitoringShowsNoneEvenIfAnOldTimestampRemains() {
        setMainContent(MonitoringState(false, null, 1_700_000_000_000L, 0), 5)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_next_check").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.next_check_none))
    }

    @Test fun stoppedMonitoringWithNoDateShowsNone() {
        setMainContent(MonitoringState(false, null, null, 0), 5)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_next_check").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.next_check_none))
    }

    private fun setMainContent(state: MonitoringState, pollIntervalMinutes: Int) {
        composeRule.setContent {
            AlertKunTheme {
                MainScreen(
                    monitoringState = state,
                    pollIntervalMinutes = pollIntervalMinutes,
                    timeZoneRevision = 0L,
                    onOpenMail = {},
                    onOpenRules = {},
                    onOpenAccount = {},
                    onOpenSettings = {},
                )
            }
        }
    }
}
