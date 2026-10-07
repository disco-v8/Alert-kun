package direct.kabu.alertkun.data.repository

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringStatusRepositoryInstrumentationTest {
    @Test fun successfulCheckPersistsTheLastSuccessfulCheckTime() = runBlocking {
        val repository = MonitoringStatusRepository(ApplicationProvider.getApplicationContext())

        repository.recordSuccess(completedAt = 42L, nextScheduledAt = null)

        assertEquals(42L, repository.current().lastSuccessfulCheckAtEpochMillis)
    }
}
