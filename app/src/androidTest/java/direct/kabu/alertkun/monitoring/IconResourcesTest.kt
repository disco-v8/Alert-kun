package direct.kabu.alertkun.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Builds notifications without handoff, mail access, settings writes, or scheduling changes. */
class IconResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val directory get() = File(context.getExternalFilesDir(null), "icon-verification").apply { mkdirs() }
    private val event = AlarmEvent("icon-fixture", "icon-fixture", "Icon preview", "Icon preview",
        null, VibrationPattern.NONE, AlarmMode.ONCE)

    private fun alarm(ongoing: Boolean): Notification {
        // Invoke only the existing display builder, never start/activate/stop playback.
        val method = AlarmController::class.java.getDeclaredMethod("notification", AlarmEvent::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(AlarmController.getInstance(context), event, "icon-fixture", false, ongoing) as Notification
    }

    @Test fun productionBuildersSelectIconsAndVectorsRenderAtSmallSizes() {
        val notifier = MonitoringStatusNotifier(context)
        val now = System.currentTimeMillis()
        assertEquals(R.drawable.ic_notification_normal, notifier.notification(now, true).smallIcon.resId)
        assertEquals(R.drawable.ic_notification_rule_match, notifier.notification(now, true, event).smallIcon.resId)
        assertEquals(R.drawable.ic_notification_normal, MonitoringHealthNotifier(context).notification(now, 0).smallIcon.resId)
        assertEquals(R.drawable.ic_notification_rule_match, alarm(false).smallIcon.resId)
        assertEquals(R.drawable.ic_notification_rule_match, alarm(true).smallIcon.resId)
        for (silent in listOf(false, true)) {
            assertNull(notifier.notification(now, silent).getLargeIcon())
            assertNull(notifier.notification(now, silent, event).getLargeIcon())
        }
        assertNull(MonitoringHealthNotifier(context).notification(now, 0).getLargeIcon())
        assertNull(alarm(false).getLargeIcon())
        assertNull(alarm(true).getLargeIcon())
        for ((name, resource) in listOf("normal" to R.drawable.ic_notification_normal,
            "rule_match" to R.drawable.ic_notification_rule_match)) {
            for (size in listOf(18, 24, 48)) {
                val drawable = context.getDrawable(resource)!!
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertTrue((0 until size).any { x -> (0 until size).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 } })
                File(directory, "$name-$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
            assertTrue(context.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
        }
        for ((name, drawable) in listOf("launcher" to context.getDrawable(R.mipmap.ic_launcher)!!,
            "launcher-round" to context.getDrawable(R.mipmap.ic_launcher_round)!!,
            "package-icon" to context.packageManager.getApplicationIcon(context.packageName))) {
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 192, 192)
            drawable.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun optInFailureNotificationKeepsSmallIconWithoutRecordingFailure() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("iconVisualPreview") == "true")
        assumeTrue(manager.areNotificationsEnabled())
        assumeTrue("Do not replace an existing failure notification", manager.activeNotifications.none { it.id == 6002 })
        val settings = SettingsRepository(context).settings.first()
        val execution = MonitoringStatusRepository(context).executionStatus()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            // Display notifier only: no Worker, failure accounting, mail, or alarm playback.
            MonitoringFailureNotifier(context).post(3, System.currentTimeMillis())
            SystemClock.sleep(800)
            val notification = manager.activeNotifications.single { it.id == 6002 }.notification
            assertEquals(R.drawable.ic_notification_rule_match, notification.smallIcon.resId)
            assertNull(notification.getLargeIcon())
            for ((name, command) in listOf("statusbar" to "cmd statusbar collapse",
                "shade" to "cmd statusbar expand-notifications")) {
                automation.executeShellCommand(command).close()
                SystemClock.sleep(2_000)
                val bitmap = automation.takeScreenshot()!!
                File(directory, "6002-failure-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            manager.cancel(6002)
            automation.executeShellCommand("cmd statusbar collapse").close()
        }
        assertEquals(settings, SettingsRepository(context).settings.first())
        assertEquals(execution, MonitoringStatusRepository(context).executionStatus())
    }

    @Test fun optInTaggedVisualPreviewsPreserveMonitoring() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        assumeTrue(InstrumentationRegistry.getArguments().getString("iconVisualPreview") == "true")
        val settings = SettingsRepository(context).settings.first()
        val execution = MonitoringStatusRepository(context).executionStatus()
        val channel = "alertkun_icon_preview"
        val silentChannel = "${channel}_silent"
        val tag = "icon-preview"
        assumeTrue(manager.areNotificationsEnabled())
        assumeTrue(manager.activeNotifications.none { it.tag == tag })
        manager.createNotificationChannel(NotificationChannel(channel, "Icon preview", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(silentChannel, "Silent icon preview", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val now = System.currentTimeMillis()
        val status = MonitoringStatusNotifier(context)
        val fixtures = listOf(
            "6003-normal" to MonitoringHealthNotifier(context).notification(now, 0),
            "6004-normal" to status.notification(now, true),
            "6004-normal-audible" to status.notification(now, false),
            "6004-rule-match" to status.notification(now, true, event),
            "5001-rule-match" to alarm(false),
            "5005-rule-match" to alarm(true),
            // Failure posting has no separate builder; render its resource without reporting a real failure.
            "6002-rule-match" to NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification_rule_match).setContentTitle("Icon preview 6002")
                .setContentText("Monitoring failure pictogram preview").build(),
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) { automation.executeShellCommand(command).close(); SystemClock.sleep(2_000) }
        fun screenshot(name: String) {
            val bitmap = automation.takeScreenshot()!!
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            for ((name, original) in fixtures) {
                val id = name.substringBefore('-').toInt()
                val preview = Notification.Builder.recoverBuilder(context, original)
                    .setChannelId(if (name.endsWith("-audible")) channel else silentChannel)
                    .setContentTitle("Icon preview $name").setContentText("Alert-kun icon preview")
                    .setContentIntent(null).setDeleteIntent(null).setActions().setOngoing(false).build()
                manager.notify(tag, id, preview)
                SystemClock.sleep(800)
                assertEquals(original.smallIcon.resId, manager.activeNotifications.single { it.tag == tag && it.id == id }.notification.smallIcon.resId)
                assertNull(manager.activeNotifications.single { it.tag == tag && it.id == id }.notification.getLargeIcon())
                shell("cmd statusbar collapse")
                screenshot("$name-statusbar")
                shell("cmd statusbar expand-notifications")
                screenshot("$name-shade")
                manager.cancel(tag, id)
            }
        } finally {
            fixtures.forEach { (name, _) -> manager.cancel(tag, name.substringBefore('-').toInt()) }
            shell("cmd statusbar collapse")
            manager.deleteNotificationChannel(channel)
            manager.deleteNotificationChannel(silentChannel)
        }
        assertEquals(settings, SettingsRepository(context).settings.first())
        assertEquals(execution.scheduleGeneration, MonitoringStatusRepository(context).executionStatus().scheduleGeneration)
        assertEquals(execution.monitoringEnabled, MonitoringStatusRepository(context).executionStatus().monitoringEnabled)
        verifyPersistedMonitoring()
    }

    @Test fun verifyPersistedMonitoring() = runBlocking {
        val execution = MonitoringStatusRepository(context).executionStatus()
        val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork(MonitoringScheduler.PERIODIC_WORK_NAME)
            .get(10, TimeUnit.SECONDS).filter { it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING) }
        if (execution.monitoringEnabled) {
            assertEquals(1, active.size)
            assertTrue("Backup belongs to current generation", "backup_generation_${execution.scheduleGeneration}" in active.single().tags)
        }
        File(directory, "monitoring.txt").writeText("enabled=${execution.monitoringEnabled}\nactivePeriodic=${active.size}\ncurrentGenerationVerified=true\n")
    }
}
