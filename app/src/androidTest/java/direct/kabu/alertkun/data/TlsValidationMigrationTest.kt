package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.model.ServerDeletePolicy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TlsValidationMigrationTest {
    @Test fun legacyModeAndUnusedCertificateColumnNormalizeOnSaveWithoutChangingCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build()
        try {
            val repository = AccountRepository(database.accountDao())
            repository.upsert(direct.kabu.alertkun.model.MailAccount("legacy", "Fixture",
                direct.kabu.alertkun.model.MailProtocol.POP3S, "example.invalid", 995,
                "fixture", "unchanged-credential-reference", false, null, true))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE accounts SET tlsValidationMode = 'PINNED_CERTIFICATE', certificateFingerprint = 'legacy-unused' WHERE id = 'legacy'")
            val account = checkNotNull(repository.get("legacy"))
            assertEquals(direct.kabu.alertkun.model.TlsValidationMode.SELF_SIGNED_ALLOWED, account.tlsValidationMode)
            assertEquals("unchanged-credential-reference", account.credentialKey)
            repository.upsert(account)
            database.openHelper.readableDatabase.query(
                "SELECT tlsValidationMode, certificateFingerprint, credentialKey FROM accounts WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst())
                assertEquals("SELF_SIGNED_ALLOWED", it.getString(0))
                assertTrue(it.isNull(1))
                assertEquals("unchanged-credential-reference", it.getString(2))
            }
        } finally { database.close() }
    }

    @Test fun versionFourPreservesDataDefaultsToStrictAndTlsModesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tls-migration-${UUID.randomUUID()}.db"
        var database: AlertKunDatabase? = null
        fun open() = Room.databaseBuilder(context, AlertKunDatabase::class.java, name)
            .addMigrations(AlertKunDatabase.MIGRATION_4_5, AlertKunDatabase.MIGRATION_5_6, AlertKunDatabase.MIGRATION_6_7).build()
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, username TEXT NOT NULL, credentialKey TEXT NOT NULL, deleteAfterReceive INTEGER NOT NULL, trustedCertificateId TEXT, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE rules (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, searchText TEXT NOT NULL, target TEXT NOT NULL, priority INTEGER NOT NULL, soundUri TEXT, vibrationPattern TEXT NOT NULL, alarmMode TEXT NOT NULL, enabled INTEGER NOT NULL, backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT', soundType TEXT NOT NULL DEFAULT 'ALARM')")
                db.execSQL("CREATE TABLE received_mails (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, remoteMessageId TEXT NOT NULL, receivedAtEpochMillis INTEGER NOT NULL, `from` TEXT, headersJson TEXT NOT NULL, subject TEXT, body TEXT, bodyFetched INTEGER NOT NULL, processingState TEXT NOT NULL, alertTriggered INTEGER NOT NULL, matchedRuleId TEXT, backgroundColor TEXT NOT NULL DEFAULT 'DEFAULT')")
                db.execSQL("CREATE UNIQUE INDEX index_received_mails_accountId_remoteMessageId ON received_mails (accountId, remoteMessageId)")
                for (flag in 0..1) db.execSQL("INSERT INTO accounts VALUES ('a$flag', 'Account', 'POP3S', 'example.invalid', 995, 'fixture', 'credential-ref-$flag', $flag, NULL, 1)")
                db.execSQL("INSERT INTO rules VALUES ('r', 'Rule', 'WARN', 'SUBJECT', 3, 'content://sounds/existing', 'PATTERN_3', 'FIVE_TIMES', 1, 'RED', 'RINGTONE')")
                db.execSQL("INSERT INTO received_mails VALUES ('m', 'a1', 'uid', 1700000000000, NULL, '{}', 'WARN', NULL, 0, 'PROCESSED', 1, 'r', 'ORANGE')")
                db.execSQL("ALTER TABLE accounts ADD COLUMN serverDeletePolicy TEXT NOT NULL DEFAULT 'NEVER'")
                db.version = 4
            }
            database = open()
            val original = AccountRepository(database.accountDao()).get("a1")!!
            assertEquals(direct.kabu.alertkun.model.TlsValidationMode.STRICT, original.tlsValidationMode)
            assertTrue(original.deleteAfterReceive)
            assertEquals(ServerDeletePolicy.NEVER, AccountRepository(database.accountDao()).get("a0")!!.serverDeletePolicy)
            val mail = database.receivedMailDao().getById("m")!!
            val rule = database.ruleDao().getById("r")!!
            for (mode in direct.kabu.alertkun.model.TlsValidationMode.entries) {
                val current = checkNotNull(database)
                val updated = original.copy(tlsValidationMode = mode)
                AccountRepository(current.accountDao()).upsert(updated)
                current.close(); database = open()
                assertEquals(updated, AccountRepository(database.accountDao()).get("a1"))
                assertEquals("credential-ref-1", updated.credentialKey)
                assertEquals(mail, database.receivedMailDao().getById("m"))
                assertEquals(rule, database.ruleDao().getById("r"))
                assertEquals(ServerDeletePolicy.NEVER, AccountRepository(database.accountDao()).get("a0")!!.serverDeletePolicy)
            }
        } finally { database?.close(); context.deleteDatabase(name) }
    }
}
