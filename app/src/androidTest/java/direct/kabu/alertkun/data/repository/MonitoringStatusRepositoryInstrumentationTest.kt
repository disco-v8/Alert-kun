package direct.kabu.alertkun.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Isolated test file: never rewrites the device's actual monitoring state. */
class MonitoringStatusRepositoryInstrumentationTest {
    @Test fun successfulCheckPersistsTimeAndTriggerAcrossStoreReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "monitoring-backup-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = MonitoringStatusRepository.forStore(store)
            val generation = repository.changeSchedule(true, 900_000L, "PERIODIC", 15)
            repository.recordCheckStarted(40L, generation)
            repository.recordSuccess(42L, MailCheckTrigger.FCM, generation)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = reopenedScope) { file }
            val repository = MonitoringStatusRepository.forStore(store)
            assertEquals(42L, repository.current().lastSuccessfulCheckAtEpochMillis)
            assertEquals(MailCheckTrigger.FCM, repository.executionStatus().lastSuccessfulTrigger)
            assertEquals(900_000L, repository.executionStatus().nextBackupAtEpochMillis)
        } finally { reopenedScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
