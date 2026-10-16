package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.model.ServerDeletePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LocalMailRetentionMigrationTest {
    @Test fun versionSixPreservesContentAndRedactedIdentitySurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-retention-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AlertKunDatabase::class.java, name)
            .addMigrations(AlertKunDatabase.MIGRATION_6_7).build()
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, credentialKey TEXT NOT NULL, deleteAfterReceive INTEGER NOT NULL, trustedCertificateId TEXT, enabled INTEGER NOT NULL, serverDeletePolicy TEXT NOT NULL DEFAULT 'NEVER', tlsValidationMode TEXT NOT NULL DEFAULT 'STRICT', certificateFingerprint TEXT)")
                db.execSQL("CREATE TABLE rules (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, searchText TEXT NOT NULL, target TEXT NOT NULL, priority INTEGER NOT NULL, soundUri TEXT, vibrationPattern TEXT NOT NULL, alarmMode TEXT NOT NULL, enabled INTEGER NOT NULL, backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT', soundType TEXT NOT NULL DEFAULT 'ALARM')")
                db.execSQL("CREATE TABLE received_mails (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, remoteMessageId TEXT NOT NULL, receivedAtEpochMillis INTEGER NOT NULL, `from` TEXT, headersJson TEXT NOT NULL, subject TEXT, body TEXT, bodyFetched INTEGER NOT NULL, processingState TEXT NOT NULL, alertTriggered INTEGER NOT NULL, matchedRuleId TEXT, backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT', serverDeleteResolution TEXT NOT NULL DEFAULT 'PENDING')")
                db.execSQL("CREATE UNIQUE INDEX index_received_mails_accountId_remoteMessageId ON received_mails (accountId, remoteMessageId)")
                db.execSQL("INSERT INTO received_mails VALUES ('m','a','fixture-uid',1700000000000,'fixture sender','{}','fixture subject','fixture body',1,'PROCESSED',1,'r','RED','PENDING')")
                db.version = 6
            }
            open().let { db ->
                try {
                    val mail = checkNotNull(db.receivedMailDao().getById("m"))
                    assertFalse(mail.locallyDeleted)
                    assertEquals("fixture subject", mail.subject); assertEquals("fixture body", mail.body)
                    assertTrue(mail.alertTriggered)
                    assertEquals(1, db.receivedMailDao().purgeLocalMail(ServerDeletePolicy.AFTER_1_WEEK, 1_700_000_000_000L + 7 * 86_400_000L))
                } finally { db.close() }
            }
            open().let { db ->
                try {
                    val mail = checkNotNull(db.receivedMailDao().getByRemoteMessageId("a", "fixture-uid"))
                    assertTrue(mail.locallyDeleted); assertNull(mail.body); assertNull(mail.subject)
                    assertEquals(1_700_000_000_000L, mail.receivedAtEpochMillis)
                    assertEquals(1, db.receivedMailDao().getDeleteCandidatesForAccount("a").size)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
