package direct.kabu.alertkun.monitoring

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.LocalMailRetention
import direct.kabu.alertkun.model.ServerDeletePolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class LocalMailCleanupTest {
    @Test fun defaultSevenDaysAndAllOptionsReuseOnlyExistingRetentionSetting() = runBlocking {
        withStore { repository, store ->
            assertEquals(7, repository.settings.first().mailRetentionDays)
            store.edit { it[intPreferencesKey("mail_retention_days")] = 15 }
            assertEquals(15, repository.settings.first().mailRetentionDays)
            val before = repository.settings.first()
            ServerDeletePolicy.entries.forEach { policy ->
                repository.updateMailRetentionPolicy(policy)
                val updated = repository.settings.first()
                assertEquals(policy, LocalMailRetention.policy(updated.mailRetentionDays))
                assertEquals(before.copy(mailRetentionDays = updated.mailRetentionDays), updated)
                assertEquals(setOf("mail_retention_days"), store.data.first().asMap().keys.map { it.name }.toSet())
            }
        }
    }

    @Test fun firstActualCheckOncePerHourAcrossTriggersAndNewInstancesEvenAfterFailure() = runBlocking {
        withStore { repository, _ ->
            val events = mutableListOf<String>()
            var calls = 0
            val start = LocalMailRetention.hourStart(1_800_000_000_000L)
            val purge: suspend (ServerDeletePolicy, Long) -> Int = { policy, _ ->
                assertEquals(ServerDeletePolicy.AFTER_1_WEEK, policy)
                calls++
                if (calls == 1) error("fixture storage failure")
                2
            }
            LocalMailCleanup(repository, purge) { event, _ -> events += event }.run(start)
            assertEquals(1, calls)
            assertTrue(events.contains("LOCAL_MAIL_CLEANUP_FAILURE"))
            // FCM / backup / manual all enter the same path; another instance must still see the durable claim.
            repeat(3) { LocalMailCleanup(repository, purge).run(start + 60_000 * (it + 1)) }
            assertEquals(1, calls)
            LocalMailCleanup(repository, purge).run(start + 3_600_000)
            assertEquals(2, calls)
            LocalMailCleanup(repository, purge).run(start) // Clock reversal does not repeat an old slot.
            assertEquals(2, calls)
            repository.updateMailRetentionPolicy(ServerDeletePolicy.NEVER)
            LocalMailCleanup(repository, purge).run(start + 7_200_000)
            assertEquals(2, calls)
        }
    }

    @Test fun concurrentClaimsAndStoreReopenDoNotRepeatHour() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "local-cleanup-${UUID.randomUUID()}.preferences_pb")
        val hour = LocalMailRetention.hourStart(1_800_000_000_000L)
        suspend fun phase(reopen: Boolean) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val repository = SettingsRepository.forStore(PreferenceDataStoreFactory.create(scope = scope) { file })
                if (reopen) assertFalse(repository.claimLocalMailCleanup(hour))
                else assertEquals(1, coroutineScope { (1..8).map { async { repository.claimLocalMailCleanup(hour) } }.awaitAll() }.count { it })
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        try { phase(false); phase(true) } finally { file.delete() }
    }

    private suspend fun withStore(block: suspend (SettingsRepository, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "local-cleanup-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(SettingsRepository.forStore(store), store)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); file.delete() }
    }
}
