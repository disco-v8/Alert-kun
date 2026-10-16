package direct.kabu.alertkun.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DemoModeSettingsTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    @Test fun absentDemoKeyDefaultsFalseWithoutWritingAnything() = runBlocking {
        val store = Store()
        val repository = SettingsRepository.forStore(store)
        assertFalse(repository.settings.first().demoModeEnabled)
        assertTrue(store.data.value.asMap().isEmpty())
    }

    @Test fun demoToggleAddsOnlyOneKeyAndSurvivesRepositoryRecreation() = runBlocking {
        val store = Store()
        val repository = SettingsRepository.forStore(store)
        repository.update(direct.kabu.alertkun.model.MonitoringSettings(pollIntervalMinutes = 17,
            mailRetentionDays = 0, consecutiveFailureThreshold = 6, monitoringEnabled = true))
        repository.updateMyDnsMasterId("fixture-id")
        store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("unknown_future_key")] = "preserve" } }
        val before = store.data.value.asMap()
        val demoKey = booleanPreferencesKey("demo_mode_enabled")
        repository.updateDemoModeEnabled(true)
        assertEquals(before, store.data.value.asMap().filterKeys { it != demoKey })
        assertTrue(SettingsRepository.forStore(store).settings.first().demoModeEnabled)
        repository.updateDemoModeEnabled(false)
        assertEquals(before, store.data.value.asMap().filterKeys { it != demoKey })
        assertFalse(SettingsRepository.forStore(store).settings.first().demoModeEnabled)
        assertEquals(0, repository.settings.first().mailRetentionDays)
    }
}
