package direct.kabu.alertkun.ui.account

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
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
import direct.kabu.alertkun.R

/** In-memory Room and credentials; no network, device accounts or Keystore mutations. */
class TlsValidationSelectorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun japaneseLabelsMatchTheFourRequestedChoices() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.JAPANESE)
        })
        assertEquals("TLS証明書の検証", context.getString(R.string.tls_validation))
        assertEquals("厳密に検証する（推奨）", context.getString(R.string.tls_strict))
        assertEquals("ホスト名の不一致を許可する", context.getString(R.string.tls_hostname))
        assertEquals("自己署名証明書を許可する", context.getString(R.string.tls_self_signed))
        assertEquals("検証しない", context.getString(R.string.tls_insecure))
    }
    @Test fun dropdownOffersExactlyFourPoliciesWithoutExtraTextOrDialogs() {
        var selected by androidx.compose.runtime.mutableStateOf(TlsValidationMode.STRICT)
        compose.setContent { TlsValidationSelector(selected, true, { selected = it }) }
        assertEquals(4, TlsValidationMode.entries.size)
        for (mode in TlsValidationMode.entries) {
            compose.onNodeWithTag("tls_validation_mode").performClick()
            TlsValidationMode.entries.forEach { compose.onNodeWithTag("tls_validation_${it.name}").assertExists() }
            compose.onNodeWithTag("tls_validation_${mode.name}").performClick()
            assertEquals(mode, selected)
            // Closed selector contains only its label and selected mode (no warnings/dialogs).
            compose.onAllNodes(androidx.compose.ui.test.hasText("", substring = true)).assertCountEquals(2)
        }
    }
    @Test fun selfSignedModeTestsAndSavesWithoutApprovalUi() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(app, AlertKunDatabase::class.java).build()
        val repository = AccountRepository(database.accountDao())
        val secrets = mutableMapOf<String, String>()
        val credentials = object : CredentialStore {
            override fun load(key: String) = secrets[key]
            override fun save(key: String, password: String) { secrets[key] = password }
            override fun delete(key: String) { secrets.remove(key) }
        }
        var authenticated = 0
        val vm = AccountSettingsViewModel(app, repository, credentials, testConnection = { account, _ ->
            assertEquals(TlsValidationMode.SELF_SIGNED_ALLOWED, account.tlsValidationMode)
            authenticated++
        })
        try {
            compose.setContent { AccountSettingsScreen(viewModel = vm, onBack = {}) }
            compose.runOnIdle { vm.edit(AccountEditor(name = "Fixture", host = "example.invalid", username = "fixture",
                password = "fixture-password", tlsValidationMode = TlsValidationMode.SELF_SIGNED_ALLOWED)) }
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.testStatus == AccountOperationStatus.SUCCESS }
            assertEquals(1, authenticated)
            compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.state.value.saveStatus == AccountOperationStatus.SUCCESS }
            val id = checkNotNull(vm.state.value.editor.id)
            assertEquals(TlsValidationMode.SELF_SIGNED_ALLOWED, runBlocking { repository.get(id) }!!.tlsValidationMode)
            compose.onNodeWithTag("tls_fingerprint").assertDoesNotExist()
            compose.onNodeWithTag("tls_approve_certificate").assertDoesNotExist()
        } finally { database.close() }
    }
}
