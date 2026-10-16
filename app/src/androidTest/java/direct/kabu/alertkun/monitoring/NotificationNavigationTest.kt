package direct.kabu.alertkun.monitoring

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import direct.kabu.alertkun.MainActivity
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Sends the real 6003/6004 content PendingIntents without posting fixture notifications or changing settings. */
class NotificationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    @Test fun statusAndHealthReuseActivityReturnToMainAndAlarmStillOpensMail() {
        val original = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        run {
            compose.onNodeWithTag("monitoring_status").assertIsDisplayed()
            val created = AtomicInteger()
            val callbacks = creationCounter(created)
            val app = context.applicationContext as Application
            app.registerActivityLifecycleCallbacks(callbacks)
            try {
                fun assertReused(action: String) {
                    instrumentation.runOnMainSync {
                        assertSame(original, ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).single { it is MainActivity })
                        assertEquals(action, original.intent.action)
                        assertEquals(flags, original.intent.flags and flags)
                    }
                    assertEquals("Notification must not create another MainActivity", 0, created.get())
                }
                // Includes both sound/silent variants and the health notification, with repeated taps.
                val notifications = listOf(
                    MonitoringStatusNotifier(context).notification(System.currentTimeMillis(), false),
                    MonitoringStatusNotifier(context).notification(System.currentTimeMillis(), true),
                    MonitoringStatusNotifier(context).notification(System.currentTimeMillis(), false, alarmEvent()),
                    MonitoringStatusNotifier(context).notification(System.currentTimeMillis(), true, alarmEvent()),
                    MonitoringHealthNotifier(context).notification(System.currentTimeMillis(), 0))
                notifications.forEach { notification ->
                    compose.onNodeWithTag("main_open_settings").performClick()
                    compose.onNodeWithTag("monitoring_save").performScrollTo().assertIsDisplayed()
                    notification.contentIntent.send()
                    instrumentation.waitForIdleSync()
                    compose.onNodeWithTag("monitoring_status").assertIsDisplayed()
                    assertReused(MainActivity.ACTION_OPEN_MAIN)
                    notification.contentIntent.send()
                    instrumentation.waitForIdleSync()
                    compose.onNodeWithTag("monitoring_status").assertIsDisplayed()
                    assertReused(MainActivity.ACTION_OPEN_MAIN)
                }
                // Existing alarm action/flags, without starting sound or issuing another mail check.
                for (ongoing in listOf(false, true)) {
                    alarmNotification(ongoing).contentIntent.send()
                    instrumentation.waitForIdleSync()
                    compose.onNodeWithTag("monitoring_status").assertDoesNotExist()
                    compose.onNodeWithText(context.getString(R.string.received_mail)).assertIsDisplayed()
                    assertReused(AlarmController.ACTION_OPEN_MAIL)
                }
                notifications.first().contentIntent.send()
                instrumentation.waitForIdleSync()
                compose.onNodeWithTag("monitoring_status").assertIsDisplayed()
                assertReused(MainActivity.ACTION_OPEN_MAIN)
            } finally {
                app.unregisterActivityLifecycleCallbacks(callbacks)
                instrumentation.runOnMainSync { original.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    @Test fun coldAlarmAndRecreationKeepMailWhileMainActionKeepsMain() {
        listOf(AlarmController.ACTION_OPEN_MAIL, MainActivity.ACTION_OPEN_MAIN).forEach { action ->
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                fun assertDestination() {
                    if (action == MainActivity.ACTION_OPEN_MAIN) compose.onNodeWithTag("monitoring_status").assertIsDisplayed()
                    else {
                        compose.onNodeWithTag("monitoring_status").assertDoesNotExist()
                        compose.onNodeWithText(context.getString(R.string.received_mail)).assertIsDisplayed()
                    }
                }
                assertDestination()
                scenario.recreate()
                assertDestination()
            }
        }
    }

    private fun alarmEvent() = AlarmEvent("icon-navigation", "icon-navigation", "Icon preview", "Icon preview",
        null, VibrationPattern.NONE, AlarmMode.ONCE)

    private fun alarmNotification(ongoing: Boolean): android.app.Notification {
        val method = AlarmController::class.java.getDeclaredMethod("notification", AlarmEvent::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(AlarmController.getInstance(context), alarmEvent(), "icon-navigation", false, ongoing) as android.app.Notification
    }

    private fun creationCounter(count: AtomicInteger) = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) count.incrementAndGet() }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
