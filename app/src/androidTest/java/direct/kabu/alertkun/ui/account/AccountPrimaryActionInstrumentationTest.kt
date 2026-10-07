package direct.kabu.alertkun.ui.account

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.R
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccountPrimaryActionInstrumentationTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val editor = AccountEditor(name = "Test", host = "example.invalid", username = "tester", password = "test-only-password")

    @Test fun primaryButtonShowsAllOperationStatesAndCallsOnlyTheAppropriateAction() {
        val state = mutableStateOf(AccountSettingsState(editor = editor, formRevision = 1))
        var tests = 0
        var saves = 0
        compose.setContent { Column { AccountPrimaryActionContent(state.value, { tests++ }, { saves++ }) } }
        val button = compose.onNodeWithTag("account_primary_action")
        button.assertIsEnabled().assertTextEquals(application.getString(R.string.test_connection)).performClick()
        assertEquals(1, tests)
        compose.runOnIdle { state.value = state.value.copy(testStatus = AccountOperationStatus.RUNNING) }
        button.assertIsNotEnabled().assertTextEquals(application.getString(R.string.connection_testing))
        compose.onNodeWithTag("account_primary_progress").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(testStatus = AccountOperationStatus.SUCCESS, verifiedRevision = 1) }
        button.assertIsEnabled().assertTextEquals(application.getString(R.string.account_save_settings)).performClick()
        assertEquals(1, saves)
        compose.runOnIdle { state.value = state.value.copy(saveStatus = AccountOperationStatus.RUNNING) }
        button.assertIsNotEnabled().assertTextEquals(application.getString(R.string.account_saving))
        compose.runOnIdle { state.value = state.value.copy(saveStatus = AccountOperationStatus.SUCCESS, savedRevision = 1) }
        button.assertIsNotEnabled().assertTextEquals(application.getString(R.string.account_saved_action))
        compose.runOnIdle { state.value = state.value.edited(editor.copy(host = "changed.invalid")) }
        button.assertIsEnabled().assertTextEquals(application.getString(R.string.test_connection))
        compose.onNodeWithTag("account_connection_status").assertDoesNotExist()
        compose.onNodeWithTag("account_save_status").assertDoesNotExist()
    }

    @Test fun allFieldEditsRequireRetestAndCannotSaveUsingAnOldSuccess() {
        val db = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        try {
            val repository = AccountRepository(db.accountDao())
            val vm = AccountSettingsViewModel(application, repository, MemoryCredentials()) { _, _ -> }
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.runOnIdle { vm.edit(editor) }
            val changes: List<(AccountEditor) -> AccountEditor> = listOf(
                { it.copy(host = "other.invalid") }, { it.copy(port = "993") },
                { it.copy(username = "other") }, { it.copy(protocol = MailProtocol.IMAPS) },
                { it.copy(password = "new-test-password") }, { it.copy(name = "Other") },
                { it.copy(enabled = !it.enabled) },
            )
            changes.forEach { change ->
                compose.runOnIdle { vm.test() }
                compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
                assertEquals(vm.state.value.formRevision, vm.state.value.verifiedRevision)
                compose.runOnIdle { vm.edit(change(vm.state.value.editor)); vm.save() }
                assertEquals(AccountPrimaryAction.TEST, vm.state.value.primaryAction)
                assertEquals(AccountOperationStatus.IDLE, vm.state.value.saveStatus)
                assertNull(vm.state.value.verifiedRevision)
            }
            assertTrue(runBlocking { repository.accounts.first() }.isEmpty())
        } finally { db.close() }
    }

    @Test fun primaryActionScrollsWithFormInsteadOfStayingInAFooter() {
        val db = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        try {
            val vm = AccountSettingsViewModel(application, AccountRepository(db.accountDao()), MemoryCredentials()) { _, _ -> }
            compose.setContent { AccountSettingsScreen({}, vm, Modifier.height(320.dp)) }
            compose.runOnIdle { vm.edit(editor) }
            val button = compose.onNodeWithTag("account_primary_action")
            button.assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("account_name").performScrollTo().assertIsDisplayed()
            button.assertIsNotDisplayed()
        } finally { db.close() }
    }

    @Test fun saveProgressSavedStateAndCredentialKeepOrReplaceFollowTheVerifiedForm() {
        val db = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val credentials = MemoryCredentials {
            saveStarted.countDown()
            check(releaseSave.await(10, TimeUnit.SECONDS))
        }
        try {
            val vm = AccountSettingsViewModel(application, AccountRepository(db.accountDao()), credentials) { account, secrets ->
                assertFalse(secrets.load(account.credentialKey).isNullOrEmpty())
            }
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.runOnIdle { vm.edit(editor); vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { saveStarted.count == 0L }
            assertEquals(AccountOperationStatus.RUNNING, vm.state.value.saveStatus)
            compose.onNodeWithTag("account_primary_action").assertIsNotEnabled()
                .assertTextEquals(application.getString(R.string.account_saving))
            releaseSave.countDown()
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            compose.onNodeWithTag("account_primary_action").assertIsNotEnabled()
                .assertTextEquals(application.getString(R.string.account_saved_action))
            assertEquals("", vm.state.value.editor.password)
            assertTrue(vm.state.value.editor.hasSavedPassword)
            val initialWrites = credentials.saveCalls
            compose.runOnIdle { vm.edit(vm.state.value.editor.copy(name = "Renamed")); vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.runOnIdle { vm.save() }
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            assertEquals(initialWrites, credentials.saveCalls)
            compose.runOnIdle { vm.edit(vm.state.value.editor.copy(password = "replacement-test-password")); vm.test() }
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.runOnIdle { vm.save() }
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            assertEquals(initialWrites + 1, credentials.saveCalls)
        } finally { releaseSave.countDown(); db.close() }
    }

    private class MemoryCredentials(private val beforeSave: () -> Unit = {}) : CredentialStore {
        private val values = mutableMapOf<String, String>()
        var saveCalls = 0
        override fun load(key: String) = values[key]
        override fun save(key: String, password: String) { beforeSave(); saveCalls++; values[key] = password }
        override fun delete(key: String) { values.remove(key) }
    }
}
