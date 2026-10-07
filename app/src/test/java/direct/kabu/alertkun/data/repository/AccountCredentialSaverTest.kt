package direct.kabu.alertkun.data.repository

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.data.entity.MailAccountEntity
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccountCredentialSaverTest {
    private val account = MailAccount(UUID.randomUUID().toString(), "Test", MailProtocol.POP3S,
        "example.invalid", 995, "tester", "", false, null, true).let { it.copy(credentialKey = "mail_password_${it.id}") }

    private class Fixture {
        val accounts = mutableMapOf<String, MailAccount>()
        val secrets = mutableMapOf<String, String>()
        val events = mutableListOf<String>()
        var failSave = false
        var loseWrite = false
        var unreadable = false
        val credentials = object : CredentialStore {
            override fun load(key: String): String? {
                if (unreadable) error("Simulated unreadable credential")
                return secrets[key]
            }
            override fun save(key: String, password: String) {
                events += "credential:$key"
                if (failSave) error("Simulated persistence failure")
                if (!loseWrite) { secrets[key] = password; unreadable = false }
            }
            override fun delete(key: String) { secrets.remove(key) }
        }
        val saver = AccountCredentialSaver(credentials, { accounts[it] }, {
            events += "account:${it.enabled}"
            accounts[it.id] = it
        }, { accounts.remove(it); Unit })
    }

    @Test fun generatedIdIsPersistedBeforeCredentialAndReadBackWithSameKey() = runBlocking {
        val f = Fixture()
        val saved = f.saver.save(account, "test-secret")
        assertEquals(account.id, saved.id)
        assertEquals("mail_password_${saved.id}", saved.credentialKey)
        assertTrue(f.credentials.load(saved.credentialKey) == "test-secret")
        assertEquals(listOf("account:false", "credential:${saved.credentialKey}", "account:true"), f.events)
        assertEquals(account, f.accounts[account.id])
        assertFalse(MailAccount::class.java.declaredFields.any { it.name == "password" })
        assertFalse(MailAccountEntity::class.java.declaredFields.any { it.name == "password" })
    }

    @Test fun failureDoesNotPublishAccountOrReturnSuccess() = runBlocking {
        val f = Fixture().apply { failSave = true }
        try { f.saver.save(account, "test-secret"); fail("Expected failure") }
        catch (error: AccountSaveException) { assertEquals(AccountSaveFailure.CREDENTIAL, error.kind) }
        assertTrue(f.accounts.isEmpty())
        assertNull(f.credentials.load(account.credentialKey))
    }

    @Test fun silentCredentialWriteLossIsDetectedAndRolledBack() = runBlocking {
        val f = Fixture().apply { loseWrite = true }
        try { f.saver.save(account, "test-secret"); fail("Expected failure") }
        catch (error: AccountSaveException) { assertEquals(AccountSaveFailure.CREDENTIAL, error.kind) }
        assertTrue(f.accounts.isEmpty())
    }

    @Test fun updatingKeepsIdAndExistingCredentialWhenInputIsBlank() = runBlocking {
        val f = Fixture()
        f.accounts[account.id] = account
        f.secrets[account.credentialKey] = "test-secret"
        val saved = f.saver.save(account.copy(host = "changed.invalid"), "")
        assertEquals(account.id, saved.id)
        assertEquals(account.credentialKey, saved.credentialKey)
        assertTrue(f.credentials.load(saved.credentialKey) == "test-secret")
        assertFalse(f.events.any { it.startsWith("credential:") })
    }

    @Test fun enteringReplacementUpdatesOnlyTheSameAccountsCredential() = runBlocking {
        val f = Fixture()
        f.accounts[account.id] = account
        f.secrets[account.credentialKey] = "old-test-secret"
        f.secrets["another-account"] = "unrelated-test-secret"
        val saved = f.saver.save(account, "new-test-secret")
        assertEquals(account.id, saved.id)
        assertTrue(f.credentials.load(saved.credentialKey) == "new-test-secret")
        assertTrue(f.credentials.load("another-account") == "unrelated-test-secret")
        assertEquals(1, f.events.count { it.startsWith("credential:") })
    }

    @Test fun missingCredentialIsFailureAndRestoresPreviousAccount() = runBlocking {
        val f = Fixture()
        f.accounts[account.id] = account
        try { f.saver.save(account.copy(host = "changed.invalid"), ""); fail("Expected failure") }
        catch (error: AccountSaveException) { assertEquals(AccountSaveFailure.CREDENTIAL, error.kind) }
        assertEquals(account, f.accounts[account.id])
    }

    @Test fun newPasswordCanReplaceAnUnreadableCredential() = runBlocking {
        val f = Fixture().apply { unreadable = true; accounts[account.id] = account }
        val saved = f.saver.save(account, "replacement-test-secret")
        assertTrue(f.credentials.load(saved.credentialKey) == "replacement-test-secret")
        assertEquals(account, saved)
    }
}
