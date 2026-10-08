package direct.kabu.alertkun.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first

class MainScreenTest {
    @Test fun gatewayRegistrationProvidesApproximateNextCheckUntilFirstFcmReceipt() {
        val registeredAt = 1_700_000_000_000L
        var state by mutableStateOf(MonitoringState(true, null, registeredAt + 900_000L, 0))
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent { MainScreen(state, 3, 0, {}, {}, {}, {}) }
        composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(context.getString(R.string.next_check_pending))
        composeRule.runOnIdle { state = state.copy(lastGatewayRegistrationAtEpochMillis = registeredAt) }
        composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(context.getString(
            R.string.next_scheduled_check, formatMonitoringTimestamp(registeredAt + 180_000L)))
        composeRule.runOnIdle { state = state.copy(lastFcmReceivedAtEpochMillis = registeredAt + 60_000L,
            lastGatewayRegistrationAtEpochMillis = registeredAt + 120_000L) }
        composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(context.getString(
            R.string.next_scheduled_check, formatMonitoringTimestamp(registeredAt + 240_000L)))
        composeRule.runOnIdle { state = state.copy(isMonitoring = false) }
        composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(context.getString(R.string.next_check_none))
    }
    @Test fun nextCheckReactsToFcmReceiptAndIntervalButNeverBackupTarget() {
        val receivedAt = 1_700_000_000_000L
        var state by mutableStateOf(MonitoringState(true, null, receivedAt + 900_000L, 0, receivedAt))
        var interval by mutableStateOf(3)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent { MainScreen(state, interval, 0, {}, {}, {}, {}) }
        fun assertNext(timestamp: Long) {
            composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(
                context.getString(R.string.next_scheduled_check, formatMonitoringTimestamp(timestamp)))
        }
        assertNext(receivedAt + 180_000L)
        composeRule.runOnIdle { state = state.copy(nextScheduledCheckAtEpochMillis = receivedAt + 1_800_000L) }
        assertNext(receivedAt + 180_000L)
        composeRule.runOnIdle {
            interval = 5
            state = state.copy(lastFcmReceivedAtEpochMillis = null, referenceIntervalMinutes = 5)
        }
        composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(context.getString(R.string.next_check_pending))
        composeRule.runOnIdle { state = state.copy(lastGatewayRegistrationAtEpochMillis = receivedAt + 120_000L) }
        assertNext(receivedAt + 420_000L)
        composeRule.runOnIdle { state = state.copy(lastFcmReceivedAtEpochMillis = receivedAt + 60_000L) }
        assertNext(receivedAt + 360_000L)
        composeRule.runOnIdle { state = state.copy(isMonitoring = false) }
        composeRule.onNodeWithTag("monitoring_next_check").assertTextEquals(context.getString(R.string.next_check_none))
    }
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun durableFcmFlowUpdatesNextCheckWithoutReopeningAndIgnoresBackupSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val file = java.io.File.createTempFile("main-fcm-flow-", ".preferences_pb", context.cacheDir).apply { delete() }
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { file }
        val repository = direct.kabu.alertkun.data.repository.MonitoringStatusRepository.forStore(store)
        val settings = direct.kabu.alertkun.data.repository.SettingsRepository.forStore(store)
        val receivedAt = System.currentTimeMillis() + 60_000L
        kotlinx.coroutines.runBlocking {
            settings.updateMonitoringSettings(3, 3, true, true)
            repository.recordGatewayRegistrationSuccess(receivedAt - 1,
                settings.fcmRegistrationTargets.first().revision)
        }
        try {
            composeRule.setContent {
                val state by repository.state.collectAsState(MonitoringState(false, null, null, 0))
                MainScreen(state, 3, 0, {}, {}, {}, {})
            }
            fun assertNext(at: Long) {
                val expected = context.getString(R.string.next_scheduled_check, formatMonitoringTimestamp(at))
                composeRule.waitUntil(5_000) {
                    composeRule.onNodeWithTag("monitoring_next_check").fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Text].single().text == expected
                }
            }
            kotlinx.coroutines.runBlocking { repository.recordFcmReceived(receivedAt) }
            assertNext(receivedAt + 180_000L)
            kotlinx.coroutines.runBlocking {
                val generation = repository.changeSchedule(true, receivedAt + 900_000, "PERIODIC", 15)
                repository.recordSuccess(receivedAt + 60_000, direct.kabu.alertkun.monitoring.MailCheckTrigger.SCHEDULED_WORK, generation)
                repository.advanceBackupWindow(receivedAt + 900_000, generation, 15)
            }
            assertNext(receivedAt + 180_000L)
            kotlinx.coroutines.runBlocking { repository.recordFcmReceived(receivedAt + 120_000) }
            assertNext(receivedAt + 300_000L)
            kotlinx.coroutines.runBlocking {
                repository.recordGatewayRegistrationSuccess(receivedAt + 180_000,
                    settings.fcmRegistrationTargets.first().revision)
            }
            assertNext(receivedAt + 360_000L)
        } finally {
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            file.delete()
        }
    }

    @Test fun durableSuccessFlowUpdatesVisibleTimestampWithoutReopeningMain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val file = java.io.File.createTempFile("main-success-flow-", ".preferences_pb", context.cacheDir).apply { delete() }
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { file }
        val repository = direct.kabu.alertkun.data.repository.MonitoringStatusRepository.forStore(store)
        val initial = 1_700_000_000_000L
        val generation = kotlinx.coroutines.runBlocking { repository.changeSchedule(true, initial, "PERIODIC", 15) }
        try {
            composeRule.setContent {
                val state by repository.state.collectAsState(MonitoringState(false, null, null, 0))
                MainScreen(state, 3, 0, {}, {}, {}, {})
            }
            fun assertTimestamp(timestamp: Long) {
                val expected = context.getString(R.string.last_check, formatMonitoringTimestamp(timestamp))
                composeRule.waitUntil(5_000) {
                    composeRule.onNodeWithTag("monitoring_last_check").fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Text].single().text == expected
                }
                composeRule.onNodeWithTag("monitoring_last_check").assertTextEquals(expected)
            }
            direct.kabu.alertkun.monitoring.MailCheckTrigger.entries.forEachIndexed { index, trigger ->
                val completed = initial + index * 60_000L
                kotlinx.coroutines.runBlocking {
                    repository.recordCheckStarted(completed - 1, generation)
                    repository.recordSuccess(completed, trigger, generation)
                }
                assertTimestamp(completed)
            }
            kotlinx.coroutines.runBlocking {
                repository.advanceBackupWindow(initial + 900_000L, generation, 15)
                repository.recordFailure(initial + 1_000_000L, 3, generation)
                repository.changeSchedule(false, null)
                repository.recordSuccess(initial + 1_100_000L, direct.kabu.alertkun.monitoring.MailCheckTrigger.FCM, generation)
            }
            assertTimestamp(initial + 3 * 60_000L)
        } finally {
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            file.delete()
        }
    }

    @Test fun continuousAlarmHasProminentStopAndItDisappearsAfterStop() {
        var active by mutableStateOf(true)
        var stops = 0
        composeRule.setContent {
            MainScreen(MonitoringState(false, null, null, 0), 5, 0,
                {}, {}, {}, {}, continuousAlarmActive = active,
                onStopAlarm = { stops++; active = false })
        }
        composeRule.onNodeWithTag("main_stop_alarm").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("main_stop_alarm").assertDoesNotExist()
        assertEquals(1, stops)
    }

    @Test fun inactiveAlarmNeverShowsStop() {
        setMainContent(MonitoringState(true, null, null, 0), 5)
        composeRule.onNodeWithTag("main_stop_alarm").assertDoesNotExist()
    }

    @Test fun japaneseNextScheduleResourcesUseTheShorterLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val japanese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPANESE)
        })
        assertEquals("次回確認：2026/10/06 12:00頃", japanese.getString(R.string.next_scheduled_check, "2026/10/06 12:00"))
        assertEquals("次回確認：未定", japanese.getString(R.string.next_check_pending))
        assertEquals("次回確認：なし", japanese.getString(R.string.next_check_none))
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
        setMainContent(MonitoringState(true, null, 1_700_000_000_000L, 0), 5)
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithTag("monitoring_next_check").assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.next_check_pending))
    }

    @Test fun activeMonitoringWithDateShowsApproximateFormattedDate() {
        val timestamp = 1_700_000_000_000L
        setMainContent(MonitoringState(true, null, 1L, 0, timestamp - 300_000L), 5)
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
