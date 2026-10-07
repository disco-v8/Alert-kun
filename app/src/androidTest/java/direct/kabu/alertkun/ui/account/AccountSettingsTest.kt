package direct.kabu.alertkun.ui.account

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.security.CredentialStore
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val editor = AccountEditor(name = "Test", host = "example.invalid", username = "tester", password = "test-only-password")

    @Test fun visibilityToggleKeepsInput() {
        var visibleValue = false
        var input = "test-only-password"
        compose.setContent {
            var visible by remember { mutableStateOf(false) }
            AccountPasswordField(input, { input = it }, visible, { visible = !visible; visibleValue = visible }, false, true)
        }
        compose.runOnIdle { assertFalse(visibleValue) }
        compose.onNodeWithTag("account_password_visibility").performClick()
        compose.runOnIdle { assertTrue(visibleValue); assertEquals("test-only-password", input) }
        compose.onNodeWithTag("account_password_visibility").performClick()
        compose.runOnIdle { assertFalse(visibleValue); assertEquals("test-only-password", input) }
    }

    @Test fun savedDummyMaskNeverEntersInputAndOnlyNewInputCanBeRevealed() {
        var actualInput = ""
        compose.setContent {
            var input by remember { mutableStateOf("") }
            var visible by remember(input.isEmpty()) { mutableStateOf(false) }
            AccountPasswordField(input, { input = it; actualInput = it }, visible, { visible = !visible }, true, true)
        }
        compose.onNodeWithTag("account_password_visibility").assertDoesNotExist()
        compose.onNodeWithTag("account_password_status", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("account_password_change_hint", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("account_password").performClick()
        compose.runOnIdle { assertEquals("", actualInput) }
        compose.onNodeWithTag("account_password").performTextInput("replacement-test-password")
        compose.runOnIdle { assertTrue(actualInput == "replacement-test-password") }
        compose.onNodeWithTag("account_password_visibility").assertIsDisplayed().performClick()
        compose.onNodeWithTag("account_password").performTextClearance()
        compose.onNodeWithTag("account_password_visibility").assertDoesNotExist()
    }

    @Test fun newAccountWithEmptyPasswordShowsStatusAndEyeOnlyAfterTyping() {
        compose.setContent {
            var input by remember { mutableStateOf("") }
            AccountPasswordField(input, { input = it }, false, {}, false, true)
        }
        compose.onNodeWithTag("account_password_visibility").assertDoesNotExist()
        compose.onNodeWithTag("account_password_status", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("account_password").performTextInput("test-only-password")
        compose.onNodeWithTag("account_password_visibility").assertIsDisplayed()
    }

    @Test fun savesAndRestoresWithoutClearingAccountOrPersistingPasswordInRoom() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        val credentials = FakeCredentials()
        try {
            val repository = AccountRepository(database.accountDao())
            val vm = AccountSettingsViewModel(application, repository, credentials) { _, _ -> }
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.runOnIdle { vm.edit(editor) }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            val saved = vm.state.value.editor
            assertNotNull(saved.id)
            assertEquals(editor.host, saved.host)
            assertEquals(editor.name, saved.name)
            assertTrue(saved.hasSavedPassword)
            assertEquals("", saved.password)
            val persisted = runBlocking { repository.get(saved.id!!) }!!
            assertEquals(editor.password, credentials.load(persisted.credentialKey))
            assertFalse(persisted.credentialKey.contains(editor.password))
            val restored = AccountSettingsViewModel(application, repository, credentials)
            compose.waitUntil(5_000) { restored.state.value.editor.id == saved.id && !restored.state.value.credentialLoading }
            assertEquals(saved, restored.state.value.editor)
            val credentialWrites = credentials.saveCalls
            compose.runOnIdle { vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.runOnIdle { vm.save() }
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            assertEquals(credentialWrites, credentials.saveCalls)
            assertTrue(credentials.load(persisted.credentialKey) == editor.password)
        } finally { database.close() }
    }

    @Test fun testUsesUnsavedFormShowsRunningAndDoesNotSaveAnything() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        val credentials = FakeCredentials()
        val release = CompletableDeferred<Unit>()
        try {
            val vm = AccountSettingsViewModel(application, AccountRepository(database.accountDao()), credentials) { account, secrets ->
                assertEquals(editor.host, account.host)
                assertEquals(editor.password, secrets.load(account.credentialKey))
                release.await()
            }
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.runOnIdle { vm.edit(editor) }
            compose.onNodeWithTag("account_primary_action").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.RUNNING }
            compose.onNodeWithTag("account_primary_action").assertIsNotEnabled()
            compose.onNodeWithTag("account_connection_status").performScrollTo().assertIsDisplayed()
            release.complete(Unit)
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.onNodeWithTag("account_primary_action").performScrollTo().assertIsEnabled()
            assertEquals(editor, vm.state.value.editor)
            assertTrue(vm.state.value.accounts.isEmpty())
            assertTrue(credentials.values.isEmpty())
            assertTrue(vm.state.value.accounts.isEmpty())
        } finally { database.close() }
    }

    @Test fun savedPasswordEnablesTestAndMissingPasswordShowsVisibleReason() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        try {
            val repository = AccountRepository(database.accountDao())
            val credentials = FakeCredentials()
            val account = MailAccount("saved", "Test", MailProtocol.POP3S, "example.invalid", 995, "tester", "mail_password_saved", false, null, true)
            runBlocking { repository.upsert(account) }
            credentials.save(account.credentialKey, "test-only-password")
            val vm = AccountSettingsViewModel(application, repository, credentials)
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.waitUntil(5_000) { vm.state.value.editor.hasSavedPassword }
            assertTrue(vm.state.value.editor.password.isEmpty())
            compose.onNodeWithTag("account_primary_action").performScrollTo().assertIsEnabled()
            credentials.delete(account.credentialKey)
            compose.runOnIdle { vm.select(account) }
            compose.waitUntil(5_000) { !vm.state.value.credentialLoading }
            assertFalse(vm.state.value.editor.hasSavedPassword)
            compose.onNodeWithTag("account_primary_action").assertIsNotEnabled()
            compose.onNodeWithTag("account_connection_disabled_reason").assertIsDisplayed()
        } finally { database.close() }
    }

    @Test fun delayedSelectCannotResetRunningOrSuccessAndSavedSecretIsUsed() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val releaseTest = CompletableDeferred<Unit>()
        val account = MailAccount("saved", "Test", MailProtocol.POP3S, "example.invalid", 995, "tester", "mail_password_saved", false, null, true)
        val secrets = object : CredentialStore {
            override fun load(key: String): String {
                loadStarted.countDown()
                check(releaseLoad.await(5, TimeUnit.SECONDS))
                return "test-only-password"
            }
            override fun save(key: String, password: String) = Unit
            override fun delete(key: String) = Unit
        }
        try {
            val repository = AccountRepository(database.accountDao())
            runBlocking { repository.upsert(account) }
            val vm = AccountSettingsViewModel(application, repository, secrets) { testedAccount, credential ->
                assertEquals(account.id, testedAccount.id)
                assertTrue(credential.load(testedAccount.credentialKey) == "test-only-password")
                releaseTest.await()
            }
            compose.setContent { AccountSettingsScreen({}, vm) }
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))
            compose.runOnIdle {
                vm.edit(vm.state.value.editor.copy(password = "test-only-password"))
                vm.test()
            }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.RUNNING }
            releaseLoad.countDown()
            compose.waitUntil(5_000) { !vm.state.value.credentialLoading }
            assertEquals(AccountOperationStatus.RUNNING, vm.state.value.testStatus)
            assertTrue(vm.state.value.editor.hasSavedPassword)
            releaseTest.complete(Unit)
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.runOnIdle { vm.edit(vm.state.value.editor.copy(password = "")); vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
        } finally { releaseLoad.countDown(); releaseTest.complete(Unit); database.close() }
    }

    @Test fun encryptedCredentialCanBeLoadedByANewStoreInstance() {
        val key = "account_ui_test_${UUID.randomUUID()}"
        val store = AndroidKeystoreCredentialStore(application)
        try {
            store.save(key, "test-only-password")
            assertTrue(AndroidKeystoreCredentialStore(application).load(key) == "test-only-password")
        } finally { store.delete(key) }
    }

    @Test fun failuresKeepFormAndExposeSafeCategory() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        val credentials = FakeCredentials(failSave = true)
        var failConnection = false
        try {
            val vm = AccountSettingsViewModel(application, AccountRepository(database.accountDao()), credentials) { _, _ ->
                if (failConnection) throw MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE)
            }
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.runOnIdle { vm.edit(editor); vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.runOnIdle { vm.save() }
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.FAILURE }
            assertEquals(editor, vm.state.value.editor)
            assertEquals(AccountPrimaryAction.SAVE, vm.state.value.primaryAction)
            assertTrue(vm.state.value.primaryEnabled)
            failConnection = true
            compose.runOnIdle { vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.FAILURE }
            compose.onNodeWithTag("account_primary_action").performScrollTo().assertIsEnabled()
            assertEquals(MonitoringFailureKind.AUTHENTICATION_FAILURE, vm.state.value.failureKind)
            assertEquals(editor, vm.state.value.editor)
        } finally { database.close() }
    }

    private class FakeCredentials(private val failSave: Boolean = false) : CredentialStore {
        val values = mutableMapOf<String, String>()
        var saveCalls = 0
        override fun save(key: String, password: String) { saveCalls++; if (failSave) error("Test save failure"); values[key] = password }
        override fun load(key: String) = values[key]
        override fun delete(key: String) { values.remove(key) }
    }
}
