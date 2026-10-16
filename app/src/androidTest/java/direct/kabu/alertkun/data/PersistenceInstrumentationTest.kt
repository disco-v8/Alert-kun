package direct.kabu.alertkun.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.entity.ReceivedMailEntity
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MonitoringSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PersistenceInstrumentationTest {
    private lateinit var database: AlertKunDatabase
    private lateinit var mailRepository: MailRepository
    @Before fun setUp() { val context = ApplicationProvider.getApplicationContext<android.content.Context>(); database = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).allowMainThreadQueries().build(); mailRepository = MailRepository(database.receivedMailDao()) }
    @After fun tearDown() = database.close()
    @Test fun receivedMailConstraintsAndFinalizationWork() = runBlocking {
        database.receivedMailDao().insertReceivedMail(mail("one", "account-a"))
        try { database.receivedMailDao().insertReceivedMail(mail("duplicate", "account-a")); throw AssertionError("duplicate accepted") } catch (_: Exception) {}
        database.receivedMailDao().insertReceivedMail(mail("two", "account-b"))
        mailRepository.finalizeProcessing("one", true, "rule-1")
        val saved = database.receivedMailDao().getById("one")!!
        assertEquals(MailProcessingState.PROCESSED, saved.processingState); assertTrue(saved.alertTriggered); assertEquals("rule-1", saved.matchedRuleId)
    }
    @Test fun settingsDefaultsAndRangesWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "defaults-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = SettingsRepository.forStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            val defaults = repository.settings.first(); assertEquals(5, defaults.pollIntervalMinutes); assertEquals(7, defaults.mailRetentionDays); assertEquals(3, defaults.consecutiveFailureThreshold)
            repository.update(defaults.copy(mailRetentionDays = 0))
            assertEquals(0, repository.settings.first().mailRetentionDays)
            // SettingsRepository accepts -1..99. Zero is the existing NEVER policy, not an invalid value.
            listOf(MonitoringSettings(pollIntervalMinutes = 0), MonitoringSettings(mailRetentionDays = -2),
                MonitoringSettings(mailRetentionDays = 100), MonitoringSettings(consecutiveFailureThreshold = 100))
                .forEach { invalid -> try { repository.update(invalid); throw AssertionError("invalid value accepted") } catch (_: IllegalArgumentException) {} }
            assertEquals(0, repository.settings.first().mailRetentionDays)
        } finally { scope.cancel(); file.delete() }
    }
    private fun mail(id: String, accountId: String) = ReceivedMailEntity(id, accountId, "remote", 1, null, "{}", null, null, false, MailProcessingState.RECEIVED, false, null)
}
