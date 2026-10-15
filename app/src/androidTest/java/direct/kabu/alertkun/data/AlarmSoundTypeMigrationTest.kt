package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.MailProcessingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AlarmSoundTypeMigrationTest {
    @Test fun versionTwoDataAndSoundUriSurviveAndAllTypesPersistAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sound-migration-${UUID.randomUUID()}.db"
        var database: AlertKunDatabase? = null
        fun open() = Room.databaseBuilder(context, AlertKunDatabase::class.java, name)
            .addMigrations(AlertKunDatabase.MIGRATION_1_2, AlertKunDatabase.MIGRATION_2_3, AlertKunDatabase.MIGRATION_3_4, AlertKunDatabase.MIGRATION_4_5, AlertKunDatabase.MIGRATION_5_6, AlertKunDatabase.MIGRATION_6_7).build()
        try {
            // v2 definitions from the exported schema, including both background snapshots.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, credentialKey TEXT NOT NULL, deleteAfterReceive INTEGER NOT NULL, trustedCertificateId TEXT, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE rules (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, searchText TEXT NOT NULL, target TEXT NOT NULL, priority INTEGER NOT NULL, soundUri TEXT, vibrationPattern TEXT NOT NULL, alarmMode TEXT NOT NULL, enabled INTEGER NOT NULL, backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT')")
                db.execSQL("CREATE TABLE received_mails (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, remoteMessageId TEXT NOT NULL, receivedAtEpochMillis INTEGER NOT NULL, `from` TEXT, headersJson TEXT NOT NULL, subject TEXT, body TEXT, bodyFetched INTEGER NOT NULL, processingState TEXT NOT NULL, alertTriggered INTEGER NOT NULL, matchedRuleId TEXT, backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT')")
                db.execSQL("CREATE UNIQUE INDEX index_received_mails_accountId_remoteMessageId ON received_mails (accountId, remoteMessageId)")
                db.execSQL("INSERT INTO accounts VALUES ('a', 'Account', 'POP3S', 'example.invalid', 995, 'user', 'key-reference', 0, NULL, 1)")
                db.execSQL("INSERT INTO rules VALUES ('r', 'Rule', 'WARN', 'SUBJECT', 3, 'content://sounds/existing', 'PATTERN_3', 'UNTIL_STOPPED', 1, 'RED')")
                db.execSQL("INSERT INTO received_mails VALUES ('m', 'a', 'uid', 1, NULL, '{}', 'WARN', NULL, 0, 'PROCESSED', 1, 'r', 'ORANGE')")
                db.version = 2
            }
            database = open()
            val original = RuleRepository(database.ruleDao()).get("r")!!
            assertEquals(AlarmSoundType.ALARM, original.soundType)
            assertEquals("content://sounds/existing", original.soundUri)
            assertEquals(AlarmMode.UNTIL_STOPPED, original.alarmMode)
            assertEquals(MailBackgroundColor.RED, original.backgroundColor)
            assertEquals(3, original.priority)
            val account = database.accountDao().getById("a")!!
            val mail = database.receivedMailDao().getById("m")!!
            assertEquals(MailProcessingState.PROCESSED, mail.processingState)
            assertEquals(MailBackgroundColor.ORANGE, mail.backgroundColor)
            assertTrue(mail.alertTriggered)
            assertEquals("uid", mail.remoteMessageId)
            for (type in AlarmSoundType.entries) {
                val updated = original.copy(soundType = type, soundUri = "content://sounds/${type.name}")
                val current = checkNotNull(database)
                RuleRepository(current.ruleDao()).upsert(updated)
                current.close()
                database = open()
                assertEquals(updated, RuleRepository(database.ruleDao()).get("r"))
                assertEquals(account, database.accountDao().getById("a"))
                assertEquals(mail, database.receivedMailDao().getById("m"))
            }
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
