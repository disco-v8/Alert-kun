package direct.kabu.alertkun.alarm

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.monitoring.*
import direct.kabu.alertkun.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRulePersistenceTest {
    @Test fun allVibrationPatternsSurviveRuleRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AlertKunDatabase::class.java).build()
        try {
            val repository = RuleRepository(db.ruleDao())
            for (pattern in VibrationPattern.entries) {
                val rule = AlertRule("vibration-rule", "Rule", "WARN", RuleTarget.SUBJECT, 1,
                    null, pattern, AlarmMode.ONCE, true)
                repository.upsert(rule)
                assertEquals(rule, RuleRepository(db.ruleDao()).get(rule.id))
            }
        } finally { db.close() }
    }

    @Test fun savedModesReachAlarmEventsAndRecheckingProcessedMailDoesNotAlarmAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AlertKunDatabase::class.java).build()
        try {
            val rules = RuleRepository(db.ruleDao())
            val mails = MailRepository(db.receivedMailDao())
            val events = mutableListOf<AlarmEvent>()
            for ((mode, soundType) in AlarmMode.entries.flatMap { mode -> AlarmSoundType.entries.map { mode to it } }) {
                val rule = AlertRule("rule", "Rule", "WARN", RuleTarget.SUBJECT, 1,
                    null, VibrationPattern.PATTERN_2, mode, true, soundType = soundType)
                rules.upsert(rule)
                val remote = RemoteMail("account", "${mode.name}-${soundType.name}", "WARN", null)
                fun coordinator() = MailCheckCoordinator(
                    object : MonitoringMailStore {
                        override suspend fun receivedMails() = mails.receivedMails()
                        override suspend fun saveReceived(mail: ReceivedMail): Boolean = try {
                            mails.saveReceived(mail); true
                        } catch (_: SQLiteConstraintException) { false }
                        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) =
                            mails.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
                    },
                    object : MonitoringRuleSource { override suspend fun allRules() = RuleRepository(db.ruleDao()).allRules() },
                    object : MailReceiver {
                        override suspend fun fetchNewMessages() = listOf(remote, remote)
                        override suspend fun fetchBody(remoteMail: RemoteMail): String? = error("Subject-only rule")
                    },
                    object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { events.add(event) } },
                )
                assertEquals(1, coordinator().check().alertCount)
                assertEquals(mode, events.last().alarmMode)
                assertEquals(soundType, events.last().soundType)
                assertEquals(rule.vibrationPattern, events.last().vibrationPattern)
                val eventCount = events.size
                repeat(3) {
                    val result = coordinator().check()
                    assertTrue(result.success)
                    assertEquals(0, result.alertCount)
                }
                assertEquals(eventCount, events.size)
                assertTrue(mails.receivedMails().isEmpty())
            }
            assertEquals(12, events.size)
        } finally { db.close() }
    }

    @Test fun soundVibrationAndBothModesSurviveRuleRoundTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AlertKunDatabase::class.java).build()
        try {
            val repository = RuleRepository(db.ruleDao())
            val rule = AlertRule("alarm-rule", "Rule", "WARN", RuleTarget.SUBJECT, 1,
                "content://settings/system/notification_sound", VibrationPattern.PATTERN_3, AlarmMode.UNTIL_STOPPED, true)
            for ((mode, soundType) in AlarmMode.entries.flatMap { mode -> AlarmSoundType.entries.map { mode to it } }) {
                val updated = rule.copy(alarmMode = mode, soundType = soundType)
                repository.upsert(updated)
                assertEquals(updated, RuleRepository(db.ruleDao()).get(rule.id))
            }
        } finally { db.close() }
    }
}
