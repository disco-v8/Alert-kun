package direct.kabu.alertkun.ui.account

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerDeletePolicySelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allSevenOptionsInvalidateVerificationAndSaveThroughPrimaryAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secrets = object : CredentialStore {
            private val values = mutableMapOf<String, String>()
            override fun load(key: String) = values[key]
            override fun save(key: String, password: String) { values[key] = password }
            override fun delete(key: String) { values.remove(key) }
        }
        Room.inMemoryDatabaseBuilder(app, AlertKunDatabase::class.java).build().useDatabase { database ->
            val repository = AccountRepository(database.accountDao())
            var tested = 0
            val vm = AccountSettingsViewModel(app, repository, secrets) { account, _ ->
                assertEquals(vmPolicy, account.serverDeletePolicy); tested++
            }
            compose.setContent { AccountSettingsScreen({}, vm) }
            compose.runOnIdle { vm.edit(AccountEditor(name = "Fixture", host = "example.invalid", username = "fixture", password = "fixture-secret")) }
            assertEquals(ServerDeletePolicy.NEVER, vm.state.value.editor.serverDeletePolicy)
            for (policy in ServerDeletePolicy.entries) {
                vmPolicy = policy
                compose.onNodeWithTag("server_delete_policy").performScrollTo().performClick()
                compose.onNodeWithTag("server_delete_policy_${policy.name}").performClick()
                assertEquals(policy, vm.state.value.editor.serverDeletePolicy)
                assertEquals(AccountPrimaryAction.TEST, vm.state.value.primaryAction)
                compose.onNodeWithTag("account_primary_action").performScrollTo().performClick()
                compose.waitUntil(5_000) { vm.state.value.primaryAction == AccountPrimaryAction.SAVE }
                compose.onNodeWithTag("account_primary_action").performClick()
                compose.waitUntil(5_000) { vm.state.value.primaryAction == AccountPrimaryAction.SAVED }
                val saved = runBlocking { repository.get(checkNotNull(vm.state.value.editor.id)) }!!
                assertEquals(policy, saved.serverDeletePolicy)
                assertEquals(policy, vm.state.value.editor.serverDeletePolicy)
                compose.runOnIdle { vm.select(saved) }
                compose.waitUntil(5_000) { !vm.state.value.credentialLoading }
                assertEquals(policy, vm.state.value.editor.serverDeletePolicy)
            }
            assertEquals(7, tested)
        }
    }

    private var vmPolicy = ServerDeletePolicy.NEVER
}

private inline fun <T> AlertKunDatabase.useDatabase(block: (AlertKunDatabase) -> T): T = try { block(this) } finally { close() }
