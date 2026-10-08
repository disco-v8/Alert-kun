package direct.kabu.alertkun.ui.account

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.model.TlsValidationMode
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-memory Room and credentials; no network, device accounts or Keystore mutations. */
class TlsCertificateApprovalTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dropdownOffersExactlyTheThreeExplicitPolicies() {
        var selected by androidx.compose.runtime.mutableStateOf(TlsValidationMode.STRICT)
        compose.setContent { TlsValidationSelector(selected, true, null, null, { selected = it }, {}) }
        for (mode in TlsValidationMode.entries) {
            compose.onNodeWithTag("tls_validation_mode").performClick()
            compose.onNodeWithTag("tls_validation_${mode.name}").performClick()
            assertEquals(mode, selected)
        }
    }
    @Test fun onlyExplicitApprovalFollowedByTestAndSavePersistsPinAndChangesRequireReapproval() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(app, AlertKunDatabase::class.java).build()
        val repository = AccountRepository(database.accountDao())
        val secrets = mutableMapOf<String, String>()
        val credentials = object : CredentialStore {
            override fun load(key: String) = secrets[key]
            override fun save(key: String, password: String) { secrets[key] = password }
            override fun delete(key: String) { secrets.remove(key) }
        }
        val old = "AA:".repeat(31) + "AA"
        val replacement = "BB:".repeat(31) + "BB"
        var presented = old
        var authenticated = 0
        val vm = AccountSettingsViewModel(app, repository, credentials,
            testConnection = { account, _ ->
                if (account.certificateFingerprint != presented) throw MailReceiverException(MonitoringFailureKind.CERTIFICATE_CHANGED)
                authenticated++
            }, inspectCertificate = { presented })
        try {
            compose.setContent { AccountSettingsScreen(viewModel = vm, onBack = {}) }
            compose.runOnIdle { vm.edit(AccountEditor(name = "Fixture", host = "example.invalid", username = "fixture",
                password = "fixture-password", tlsValidationMode = TlsValidationMode.PINNED_CERTIFICATE)) }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.pendingCertificateFingerprint == old }
            assertEquals(0, authenticated)
            assertNull(vm.state.value.editor.certificateFingerprint)
            assertTrue(runBlocking { repository.accounts.first().isEmpty() })
            compose.onNodeWithTag("tls_approve_certificate").performScrollTo().performClick()
            assertEquals(old, vm.state.value.editor.certificateFingerprint)
            assertTrue(runBlocking { repository.accounts.first().isEmpty() })
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            val id = checkNotNull(vm.state.value.editor.id)
            assertEquals(old, runBlocking { repository.get(id) }!!.certificateFingerprint)
            presented = replacement
            compose.runOnIdle { vm.edit(vm.state.value.editor.copy(name = "Changed fixture")) }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.pendingCertificateFingerprint == replacement }
            assertEquals(MonitoringFailureKind.CERTIFICATE_CHANGED, vm.state.value.failureKind)
            assertEquals(old, vm.state.value.editor.certificateFingerprint)
            assertEquals(old, runBlocking { repository.get(id) }!!.certificateFingerprint)
            compose.onNodeWithTag("tls_approve_certificate").performScrollTo().performClick()
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            assertEquals(replacement, runBlocking { repository.get(id) }!!.certificateFingerprint)
            compose.runOnIdle { vm.edit(vm.state.value.editor.copy(host = "other.invalid")) }
            assertNull(vm.state.value.editor.certificateFingerprint)
        } finally { database.close() }
    }
}
