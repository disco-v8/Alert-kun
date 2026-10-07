package direct.kabu.alertkun.ui.mail

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.database.sqlite.SQLiteConstraintException
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PollAlarmSelectionInstrumentationTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val high = AlertRule("high", "High", "ALERT", RuleTarget.SUBJECT, 10, null,
        VibrationPattern.PATTERN_1, AlarmMode.ONCE, true, MailBackgroundColor.RED)
    private val low = high.copy(id = "low", name = "Low", searchText = "WARN", priority = 20,
        soundType = AlarmSoundType.NOTIFICATION, vibrationPattern = VibrationPattern.PATTERN_3,
        alarmMode = AlarmMode.FIVE_TIMES, backgroundColor = MailBackgroundColor.YELLOW)
    private val secrets = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }
    private fun coordinator(database: AlertKunDatabase, server: Pop3TestServer, sink: (AlarmEvent) -> Unit) =
        MailCheckCoordinator(object : MonitoringMailStore {
            private val repository = MailRepository(database.receivedMailDao())
            override suspend fun receivedMails() = repository.receivedMails()
            override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) = repository.getByRemoteId(accountId, remoteMessageId)
            override suspend fun saveReceived(mail: ReceivedMail): Boolean = try { repository.saveReceived(mail); true }
                catch (_: SQLiteConstraintException) { false }
            override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) =
                repository.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
            override suspend fun finalizeBatch(outcomes: List<MailProcessingOutcome>) = repository.finalizeBatch(outcomes)
        }, object : MonitoringRuleSource { override suspend fun allRules() = listOf(low, high) },
            AccountMailReceiver(listOf(server.account(ServerDeletePolicy.NEVER)), secrets),
            object : MonitoringAlarmSink { override fun start(event: AlarmEvent) = sink(event) })

    @Test fun bothTcpMailboxOrdersDisplayBothOwnColorsAndPlayOnlyHighThroughRealController() {
        val controller = AlarmController.getInstance(app)
        val notifications = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").close()
        val displayed = mutableStateOf<ReceivedMailViewModel?>(null)
        val scheme = lightColorScheme()
        compose.setContent { MaterialTheme(colorScheme = scheme) { displayed.value?.let { ReceivedMailScreen({}, viewModel = it) } } }
        try {
            for (order in listOf(listOf("alert", "warn"), listOf("warn", "alert"))) {
                controller.stop()
                Pop3TestServer(order).use { server ->
                    server.subjects = mapOf("alert" to "ALERT test", "warn" to "WARN test")
                    val database = Room.inMemoryDatabaseBuilder(app, AlertKunDatabase::class.java).build()
                    try {
                        val events = mutableListOf<AlarmEvent>()
                        val check = coordinator(database, server) { event -> events += event; controller.start(event) }
                        assertTrue(runBlocking { check.check() }.success)
                        assertEquals(1, events.size); assertEquals(high.id, events.single().ruleId)
                        await { player(controller)?.isPlaying == true }
                        assertFalse(player(controller)!!.isLooping)
                        val duration = player(controller)!!.duration.toLong()
                        val rows = runBlocking { order.map { database.receivedMailDao().getByRemoteMessageId("deletion-test", it)!! } }
                        assertTrue(rows.all { it.processingState == MailProcessingState.PROCESSED })
                        assertEquals(1, rows.count { it.alertTriggered })
                        compose.runOnIdle { displayed.value = ReceivedMailViewModel(app, MailRepository(database.receivedMailDao())) }
                        compose.waitUntil(5_000) { displayed.value!!.state.value.mails.size == 2 }
                        for (row in rows) {
                            val node = compose.onNodeWithTag("received_mail_${row.id}").assertIsDisplayed()
                            val pixels = node.captureToImage().toPixelMap()
                            val color = if (row.remoteMessageId == "alert") MailBackgroundColor.RED else MailBackgroundColor.YELLOW
                            assertEquals(color, row.backgroundColor)
                            assertEquals(color.cardColors(scheme).background.toArgb(), pixels[pixels.width - 8, pixels.height / 2].toArgb())
                        }
                        val notification = notifications.activeNotifications.single { it.id == AlarmController.NOTIFICATION_ID }.notification
                        val detail = notification.extras.getCharSequence("android.bigText").toString()
                        assertTrue(detail.contains("ALERT test")); assertFalse(detail.contains("WARN test"))
                        assertEquals(0, runBlocking { check.check() }.alertCount); assertEquals(1, events.size)
                        await(duration + 2_000) { player(controller) == null }
                        assertFalse(server.commands.any { it.startsWith("DELE") })
                        compose.runOnIdle { displayed.value = null }
                    } finally { database.close() }
                }
            }
        } finally { controller.stop(); compose.runOnIdle { displayed.value = null } }
    }

    @Test fun batchTransactionFailureRollsBackWinnerAndLoserSnapshotsTogether() = runBlocking {
        Pop3TestServer(listOf("alert", "warn")).use { server ->
            server.subjects = mapOf("alert" to "ALERT", "warn" to "WARN")
            val database = Room.inMemoryDatabaseBuilder(app, AlertKunDatabase::class.java).build()
            try {
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_low BEFORE UPDATE ON received_mails WHEN NEW.remoteMessageId = 'warn' BEGIN SELECT RAISE(ABORT, 'Fixture failure'); END")
                var handoffs = 0
                assertFalse(coordinator(database, server) { handoffs++ }.check().success)
                assertEquals(1, handoffs)
                val pending = database.receivedMailDao().getByProcessingState(MailProcessingState.RECEIVED)
                assertEquals(2, pending.size)
                assertTrue(pending.all { !it.alertTriggered && it.matchedRuleId == null && it.backgroundColor == MailBackgroundColor.DEFAULT })
                assertFalse(server.commands.any { it.startsWith("DELE") })
            } finally { database.close() }
        }
    }

    private fun player(controller: AlarmController): MediaPlayer? = synchronized(controller) {
        AlarmController::class.java.getDeclaredField("player").apply { isAccessible = true }.get(controller) as MediaPlayer?
    }
    private fun await(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("Playback condition not satisfied", condition())
    }
}
