package direct.kabu.alertkun.data.repository

import direct.kabu.alertkun.security.CredentialStore
import direct.kabu.alertkun.push.FcmTokenRegistrar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MyDnsSettingsRepositoryTest {
    @Test fun masterIdChangeDiscardsBearerWithBlankPasswordPreserved() = runBlocking {
        var id = "old"
        val credentials = MemoryCredentials()
        credentials.save(MyDnsSettingsRepository.CREDENTIAL_KEY, "password")
        credentials.save(FcmTokenRegistrar.DEVICE_TOKEN_KEY, "bearer")
        MyDnsSettingsRepository(credentials, { id }, { id = it }).save("new", "")
        assertNull(credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
        assertEquals("password", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
    }

    @Test fun passwordChangeDiscardsBearer() = runBlocking {
        val credentials = MemoryCredentials()
        credentials.save(MyDnsSettingsRepository.CREDENTIAL_KEY, "old")
        credentials.save(FcmTokenRegistrar.DEVICE_TOKEN_KEY, "bearer")
        MyDnsSettingsRepository(credentials, { "same" }, {}).save("same", "new")
        assertNull(credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
    }

    @Test fun unchangedCredentialsPreserveBearer() = runBlocking {
        val credentials = MemoryCredentials()
        credentials.save(MyDnsSettingsRepository.CREDENTIAL_KEY, "same-password")
        credentials.save(FcmTokenRegistrar.DEVICE_TOKEN_KEY, "bearer")
        val repository = MyDnsSettingsRepository(credentials, { "same" }, {})
        repository.save("same", "")
        repository.save("same", "same-password")
        assertEquals("bearer", credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY))
    }

    @Test fun bearerDeletionFailurePreventsPublishingChangedIdentity() = runBlocking {
        var id = "old"
        val credentials = object : CredentialStore {
            override fun load(key: String) = if (key == FcmTokenRegistrar.DEVICE_TOKEN_KEY) "bearer" else "password"
            override fun save(key: String, password: String) = Unit
            override fun delete(key: String) { error("Storage failure") }
        }
        try { MyDnsSettingsRepository(credentials, { id }, { id = it }).save("new", ""); fail("Expected failure") }
        catch (error: MyDnsSaveException) { assertEquals(MyDnsSaveFailure.STORAGE, error.kind) }
        assertEquals("old", id)
    }

    @Test fun savesAndReloadsIdWithoutPuttingPasswordInSettings() = runBlocking {
        var normalSetting = ""
        val credentials = MemoryCredentials()
        val repository = MyDnsSettingsRepository(credentials, { normalSetting }, { normalSetting = it })
        repository.save("  test-master  ", "private-password")
        assertEquals("test-master", normalSetting)
        assertEquals(MyDnsSavedSettings("test-master", true), repository.read())
        assertEquals("private-password", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
        assertNotNull(credentials.load(FcmTokenRegistrar.IDENTITY_VERSION_KEY))
        assertEquals(setOf(MyDnsSettingsRepository.CREDENTIAL_KEY, FcmTokenRegistrar.IDENTITY_VERSION_KEY), credentials.values.keys)
        assertFalse(normalSetting.contains("private-password"))
    }

    @Test fun emptyPasswordPreservesSavedValueAndNewInputReplacesIt() = runBlocking {
        var id = ""
        val credentials = MemoryCredentials()
        val repository = MyDnsSettingsRepository(credentials, { id }, { id = it })
        repository.save("one", "first")
        repository.save("two", "")
        assertEquals("first", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
        repository.save("two", "second")
        assertEquals("second", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
        repository.save("", "")
        assertEquals(MyDnsSavedSettings("", true), repository.read())
    }

    @Test fun settingsFailureRestoresPreviousCredential() = runBlocking {
        val credentials = MemoryCredentials()
        credentials.save(MyDnsSettingsRepository.CREDENTIAL_KEY, "old")
        val repository = MyDnsSettingsRepository(credentials, { "old-id" }, {
            if (it != "old-id") error("Storage failed")
        })
        try { repository.save("new-id", "new"); fail("Expected failure") }
        catch (error: MyDnsSaveException) { assertEquals(MyDnsSaveFailure.STORAGE, error.kind) }
        assertEquals("old", credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY))
    }

    @Test fun failedVerificationNeverPublishesNewId() = runBlocking {
        var id = "old-id"
        val credentials = object : CredentialStore {
            override fun load(key: String) = "old"
            override fun save(key: String, password: String) = Unit
            override fun delete(key: String) = Unit
        }
        try { MyDnsSettingsRepository(credentials, { id }, { id = it }).save("new-id", "new"); fail("Expected failure") }
        catch (error: MyDnsSaveException) { assertEquals(MyDnsSaveFailure.STORAGE, error.kind) }
        assertEquals("old-id", id)
    }

    @Test fun failedCompensationIsReported() = runBlocking {
        val values = mutableMapOf(MyDnsSettingsRepository.CREDENTIAL_KEY to "old")
        val credentials = object : CredentialStore {
            override fun load(key: String) = values[key]
            override fun save(key: String, password: String) {
                if (key == FcmTokenRegistrar.IDENTITY_VERSION_KEY) values[key] = password
                else error("Cannot persist or compensate password")
            }
            override fun delete(key: String) = Unit
        }
        try { MyDnsSettingsRepository(credentials, { "old-id" }, {}).save("new-id", "new"); fail("Expected failure") }
        catch (error: MyDnsSaveException) { assertEquals(MyDnsSaveFailure.ROLLBACK, error.kind) }
    }

    @Test fun identityFenceAndBearerRemovalPrecedeNewPasswordWrite() = runBlocking {
        val values = mutableMapOf(MyDnsSettingsRepository.CREDENTIAL_KEY to "old", FcmTokenRegistrar.DEVICE_TOKEN_KEY to "old-bearer")
        val credentials = object : CredentialStore {
            override fun load(key: String) = values[key]
            override fun save(key: String, password: String) {
                if (key == MyDnsSettingsRepository.CREDENTIAL_KEY) {
                    assertNotNull(values[FcmTokenRegistrar.IDENTITY_VERSION_KEY])
                    assertNull(values[FcmTokenRegistrar.DEVICE_TOKEN_KEY])
                }
                values[key] = password
            }
            override fun delete(key: String) { values.remove(key) }
        }
        MyDnsSettingsRepository(credentials, { "same" }, {}).save("same", "new")
        assertEquals("new", values[MyDnsSettingsRepository.CREDENTIAL_KEY])
    }

    private class MemoryCredentials : CredentialStore {
        val values = mutableMapOf<String, String>()
        override fun save(key: String, password: String) { values[key] = password }
        override fun load(key: String) = values[key]
        override fun delete(key: String) { values.remove(key) }
    }
}
