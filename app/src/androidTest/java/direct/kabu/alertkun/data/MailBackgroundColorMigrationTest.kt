package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.MailProcessingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class MailBackgroundColorMigrationTest {
    @Test fun versionOneRowsSurviveMigrationWithDefaultColor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "color-migration-${UUID.randomUUID()}.db"
        var migrated: AlertKunDatabase? = null
        try {
            // Exact v1 table definitions from the exported 1.json schema.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, `credentialKey` TEXT NOT NULL, `deleteAfterReceive` INTEGER NOT NULL, `trustedCertificateId` TEXT, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `searchText` TEXT NOT NULL, `target` TEXT NOT NULL, `priority` INTEGER NOT NULL, `soundUri` TEXT, `vibrationPattern` TEXT NOT NULL, `alarmMode` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `received_mails` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `remoteMessageId` TEXT NOT NULL, `receivedAtEpochMillis` INTEGER NOT NULL, `from` TEXT, `headersJson` TEXT NOT NULL, `subject` TEXT, `body` TEXT, `bodyFetched` INTEGER NOT NULL, `processingState` TEXT NOT NULL, `alertTriggered` INTEGER NOT NULL, `matchedRuleId` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_received_mails_accountId_remoteMessageId` ON `received_mails` (`accountId`, `remoteMessageId`)")
                db.execSQL("INSERT INTO rules VALUES ('r', 'Rule', 'WARN', 'SUBJECT', 1, NULL, 'NONE', 'ONCE', 1)")
                db.execSQL("INSERT INTO received_mails VALUES ('m', 'a', 'uid', 1, NULL, '{}', 'WARN', NULL, 0, 'PROCESSED', 1, 'r')")
                db.version = 1
            }
            val database = Room.databaseBuilder(context, AlertKunDatabase::class.java, name)
                .addMigrations(AlertKunDatabase.MIGRATION_1_2, AlertKunDatabase.MIGRATION_2_3, AlertKunDatabase.MIGRATION_3_4, AlertKunDatabase.MIGRATION_4_5, AlertKunDatabase.MIGRATION_5_6, AlertKunDatabase.MIGRATION_6_7).build()
            migrated = database
            assertEquals(MailBackgroundColor.DEFAULT, database.ruleDao().getById("r")!!.backgroundColor)
            assertEquals(direct.kabu.alertkun.model.AlarmSoundType.ALARM, database.ruleDao().getById("r")!!.soundType)
            val mail = database.receivedMailDao().getById("m")!!
            assertEquals(MailBackgroundColor.DEFAULT, mail.backgroundColor)
            assertEquals(MailProcessingState.PROCESSED, mail.processingState)
            assertEquals("uid", mail.remoteMessageId)
            assertEquals("r", mail.matchedRuleId)
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
        }
    }
}
