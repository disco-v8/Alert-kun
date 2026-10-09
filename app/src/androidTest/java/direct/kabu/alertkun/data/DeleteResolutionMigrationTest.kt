package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.model.ServerDeleteResolution
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeleteResolutionMigrationTest {
    @Test fun versionFivePreservesMailAndResolutionSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delete-resolution-" + UUID.randomUUID() + ".db"
        fun open() = Room.databaseBuilder(context, AlertKunDatabase::class.java, name)
            .addMigrations(AlertKunDatabase.MIGRATION_5_6).build()
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("""CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, `credentialKey` TEXT NOT NULL, `deleteAfterReceive` INTEGER NOT NULL, `trustedCertificateId` TEXT, `enabled` INTEGER NOT NULL, `serverDeletePolicy` TEXT NOT NULL DEFAULT 'NEVER', `tlsValidationMode` TEXT NOT NULL DEFAULT 'STRICT', `certificateFingerprint` TEXT, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `searchText` TEXT NOT NULL, `target` TEXT NOT NULL, `priority` INTEGER NOT NULL, `soundUri` TEXT, `vibrationPattern` TEXT NOT NULL, `alarmMode` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `backgroundColor` TEXT NOT NULL DEFAULT 'DEFAULT', `soundType` TEXT NOT NULL DEFAULT 'ALARM', PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `received_mails` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `remoteMessageId` TEXT NOT NULL, `receivedAtEpochMillis` INTEGER NOT NULL, `from` TEXT, `headersJson` TEXT NOT NULL, `subject` TEXT, `body` TEXT, `bodyFetched` INTEGER NOT NULL, `processingState` TEXT NOT NULL, `alertTriggered` INTEGER NOT NULL, `matchedRuleId` TEXT, `backgroundColor` TEXT NOT NULL DEFAULT 'DEFAULT', PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_received_mails_accountId_remoteMessageId` ON `received_mails` (`accountId`, `remoteMessageId`)""")
                db.execSQL("INSERT INTO received_mails VALUES ('m','a','fixture-uidl',1700000000000,'fixture-from','{}','fixture-subject','fixture-body',1,'PROCESSED',1,'r','RED')")
                db.version = 5
            }
            var before: direct.kabu.alertkun.data.entity.ReceivedMailEntity? = null
            open().let { db ->
                try {
                    val mail = checkNotNull(db.receivedMailDao().getById("m"))
                    before = mail
                    assertEquals(ServerDeleteResolution.PENDING, mail.serverDeleteResolution)
                    assertEquals("fixture-subject", mail.subject)
                    assertEquals("fixture-body", mail.body)
                    assertTrue(mail.alertTriggered)
                    assertEquals(1, db.receivedMailDao().getDeleteCandidatesForAccount("a").size)
                    assertEquals(1 to 0, db.receivedMailDao().resolveDeletes("a", setOf("fixture-uidl"), emptySet()))
                } finally { db.close() }
            }
            open().let { db ->
                try {
                    assertEquals(before!!.copy(serverDeleteResolution = ServerDeleteResolution.DELETED), db.receivedMailDao().getById("m"))
                    assertTrue(db.receivedMailDao().getDeleteCandidatesForAccount("a").isEmpty())
                    assertEquals(1, db.receivedMailDao().getProcessedForAccount("a").size)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
