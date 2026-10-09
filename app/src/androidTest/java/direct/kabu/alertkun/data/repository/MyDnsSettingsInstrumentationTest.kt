package direct.kabu.alertkun.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.push.fcmRegistrationTargets
import direct.kabu.alertkun.push.FcmTokenRegistrar
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Uses isolated test settings/credential preferences; never modifies the user's configuration. */
class MyDnsSettingsInstrumentationTest {
    @Test fun persistsMasterIdAndEncryptsPasswordAcrossRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "mydns-$suffix.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("mydns-test-$suffix-$name", mode)
        }
        val credentials = AndroidKeystoreCredentialStore(isolated)
        try {
            val settings = SettingsRepository.forStore(store)
            MyDnsSettingsRepository(settings, credentials).save("  test-master  ", "private-test-password")
            val recreated = MyDnsSettingsRepository(SettingsRepository.forStore(store), AndroidKeystoreCredentialStore(isolated))
            assertEquals(MyDnsSavedSettings("test-master", true), recreated.read())
            recreated.save("test-master", "")
            credentials.save(FcmTokenRegistrar.DEVICE_TOKEN_KEY, "private-test-bearer")
            assertEquals("private-test-bearer", AndroidKeystoreCredentialStore(isolated).load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
            val encryptedBearer = isolated.getSharedPreferences("alertkun_encrypted_credentials", Context.MODE_PRIVATE)
                .getString(FcmTokenRegistrar.DEVICE_TOKEN_KEY, null)
            assertFalse(encryptedBearer!!.contains("private-test-bearer"))
            recreated.save("test-master", "")
            assertEquals("private-test-bearer", credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
            assertEquals("private-test-password", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
            assertFalse(store.data.first().asMap().values.any { it.toString().contains("private-test-password") })
            val encrypted = isolated.getSharedPreferences("alertkun_encrypted_credentials", Context.MODE_PRIVATE)
                .getString(MyDnsSettingsRepository.CREDENTIAL_KEY, null)
            assertNotNull(encrypted)
            assertFalse(encrypted!!.contains("private-test-password"))
            recreated.save("test-master", "replacement-test-password")
            assertNull(credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
            assertEquals("replacement-test-password", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
        } finally {
            credentials.delete(MyDnsSettingsRepository.CREDENTIAL_KEY)
            credentials.delete(FcmTokenRegistrar.DEVICE_TOKEN_KEY)
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
        // Reopen the same file with a fresh DataStore, proving disk persistence.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = PreferenceDataStoreFactory.create(scope = newScope) { file }
            assertEquals("test-master", SettingsRepository.forStore(reopened).myDnsMasterId.first())
        } finally { newScope.cancel() }
    }

    @Test fun masterIdAndIntervalChangesBecomeRegistrationTargets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            File(context.cacheDir, "mydns-target-${UUID.randomUUID()}.preferences_pb")
        }
        val settings = SettingsRepository.forStore(store)
        val events = Channel<direct.kabu.alertkun.push.FcmRegistrationTarget>(Channel.UNLIMITED)
        val observer = launch { settings.fcmRegistrationTargets().collect { events.send(it) } }
        try {
            assertFalse(withTimeout(5000) { events.receive() }.configured)
            settings.updateMyDnsMasterId("one")
            assertEquals("one", withTimeout(5000) { events.receive() }.masterId)
            settings.updateMyDnsMasterId("two")
            assertEquals("two", withTimeout(5000) { events.receive() }.masterId)
            settings.update(settings.settings.first().copy(pollIntervalMinutes = 3))
            assertEquals(3, withTimeout(5000) { events.receive() }.intervalMinutes)
            settings.updateMyDnsMasterId("")
            assertFalse(withTimeout(5000) { events.receive() }.configured)
        } finally { observer.cancel(); scope.cancel() }
    }
}
