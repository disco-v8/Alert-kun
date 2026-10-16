package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.entity.ReceivedMailEntity
import direct.kabu.alertkun.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalMailRetentionTest {
    @Test fun purgeClearsExpiredContentButProtectsRecoveryAndDedupAndServerDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build()
        try {
            val dao = db.receivedMailDao()
            val now = 1_800_000_000_000L
            val cutoff = now - 7 * 86_400_000L
            fun fixture(id: String, at: Long, state: MailProcessingState = MailProcessingState.PROCESSED) = ReceivedMailEntity(
                id, "fixture-account", "uid-$id", at, "private sender", "{\"fixture\":\"private header\"}",
                "private subject", "private body", true, state, true, "rule", MailBackgroundColor.RED)
            val expired = fixture("expired", cutoff)
            val recent = fixture("recent", cutoff + 1)
            val pending = fixture("pending", cutoff - 1, MailProcessingState.RECEIVED)
            val invalid = fixture("invalid", 0)
            listOf(expired, recent, pending, invalid).forEach { dao.insertReceivedMail(it) }
            assertEquals(1, dao.purgeLocalMail(ServerDeletePolicy.AFTER_1_WEEK, now))
            val purged = checkNotNull(dao.getById("expired"))
            assertTrue(purged.locallyDeleted)
            assertNull(purged.from); assertNull(purged.subject); assertNull(purged.body)
            assertEquals("{}", purged.headersJson); assertFalse(purged.bodyFetched)
            assertNull(purged.matchedRuleId)
            assertEquals(listOf("invalid", "pending", "recent"), dao.observeAll().first().map { it.id }.sorted())
            assertEquals(recent, dao.getById("recent")); assertEquals(pending, dao.getById("pending"))
            assertEquals(invalid, dao.getById("invalid"))
            assertEquals(purged, dao.getByRemoteMessageId(expired.accountId, expired.remoteMessageId))
            try { dao.insertReceivedMail(expired.copy(id = "duplicate")); fail("UNIQUE identity must remain") }
            catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertTrue(dao.getDeleteCandidatesForAccount(expired.accountId).any { it.id == expired.id })
            assertEquals(1 to 0, dao.resolveDeletes(expired.accountId, setOf(expired.remoteMessageId), emptySet()))
            assertEquals(ServerDeleteResolution.DELETED, dao.getById(expired.id)!!.serverDeleteResolution)
            assertEquals(0, dao.purgeLocalMail(ServerDeletePolicy.NEVER, now))
            assertEquals(0, dao.purgeLocalMail(ServerDeletePolicy.AFTER_1_WEEK, now))
        } finally { db.close() }
    }

    @Test fun immediatelyPurgesNewProcessedMailButLeavesDailyAlertCount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build()
        try {
            val now = 1_800_000_000_000L
            val dao = db.receivedMailDao()
            dao.insertReceivedMail(ReceivedMailEntity("m", "a", "remote", now, null, "{}", "fixture", null,
                false, MailProcessingState.PROCESSED, true, null))
            assertEquals(1, dao.purgeLocalMail(ServerDeletePolicy.IMMEDIATELY, now))
            assertTrue(dao.observeAll().first().isEmpty())
            assertEquals(1, dao.countTriggeredAlerts(now - 86_400_000, now))
        } finally { db.close() }
    }
}
