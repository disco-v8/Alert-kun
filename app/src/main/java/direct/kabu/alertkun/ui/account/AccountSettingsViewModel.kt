package direct.kabu.alertkun.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.data.repository.AccountCredentialSaver
import direct.kabu.alertkun.data.repository.AccountSaveException
import direct.kabu.alertkun.data.repository.AccountSaveFailure
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import direct.kabu.alertkun.security.CredentialStore
import direct.kabu.alertkun.security.CredentialAccessException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountEditor(
    val id: String? = null,
    val name: String = "",
    val protocol: MailProtocol = MailProtocol.POP3S,
    val host: String = "",
    val port: String = "995",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val hasSavedPassword: Boolean = false,
    val serverDeletePolicy: ServerDeletePolicy = ServerDeletePolicy.NEVER,
) {
    val connectionIssues: List<AccountConnectionIssue> get() = buildList {
        if (host.isBlank()) add(AccountConnectionIssue.HOST)
        if (port.toIntOrNull() !in 1..65535) add(AccountConnectionIssue.PORT)
        if (username.isBlank()) add(AccountConnectionIssue.USERNAME)
        if (password.isEmpty() && !hasSavedPassword) add(AccountConnectionIssue.PASSWORD)
    }
    val canConnect: Boolean get() = connectionIssues.isEmpty()
    val canSave: Boolean get() = name.isNotBlank() && canConnect
}

enum class AccountConnectionIssue { HOST, PORT, USERNAME, PASSWORD }
enum class AccountOperationStatus { IDLE, RUNNING, SUCCESS, FAILURE }
enum class AccountPrimaryAction { TEST, TESTING, SAVE, SAVING, SAVED }

data class AccountSettingsState(
    val editor: AccountEditor = AccountEditor(),
    val accounts: List<MailAccount> = emptyList(),
    val saveStatus: AccountOperationStatus = AccountOperationStatus.IDLE,
    val testStatus: AccountOperationStatus = AccountOperationStatus.IDLE,
    val failureKind: MonitoringFailureKind? = null,
    val credentialLoading: Boolean = false,
    val saveFailure: AccountSaveFailure? = null,
    val formRevision: Long = 0,
    val verifiedRevision: Long? = null,
    val savedRevision: Long? = null,
) {
    val busy: Boolean get() = saveStatus == AccountOperationStatus.RUNNING || testStatus == AccountOperationStatus.RUNNING
    val currentFormVerified: Boolean get() = verifiedRevision == formRevision
    val primaryAction: AccountPrimaryAction get() = when {
        saveStatus == AccountOperationStatus.RUNNING -> AccountPrimaryAction.SAVING
        testStatus == AccountOperationStatus.RUNNING -> AccountPrimaryAction.TESTING
        savedRevision == formRevision -> AccountPrimaryAction.SAVED
        currentFormVerified -> AccountPrimaryAction.SAVE
        else -> AccountPrimaryAction.TEST
    }
    val primaryEnabled: Boolean get() = when (primaryAction) {
        AccountPrimaryAction.TEST -> editor.canConnect
        AccountPrimaryAction.SAVE -> editor.canSave
        else -> false
    }

    /** All user-editable fields invalidate verification; no password snapshot/hash is retained. */
    fun edited(newEditor: AccountEditor): AccountSettingsState {
        if (busy || newEditor == editor) return this
        return copy(editor = newEditor, formRevision = formRevision + 1,
            verifiedRevision = null, savedRevision = null, saveStatus = AccountOperationStatus.IDLE,
            testStatus = AccountOperationStatus.IDLE, failureKind = null,
            credentialLoading = newEditor.id == editor.id && credentialLoading, saveFailure = null)
    }
}

class AccountSettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: AccountRepository = AccountRepository(AlertKunDatabase.getInstance(application).accountDao()),
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(application),
    private val testConnection: suspend (MailAccount, CredentialStore) -> Unit = { account, secrets -> AccountMailReceiver(listOf(account), secrets).testConnection(account) },
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AccountSettingsState())
    val state = mutableState.asStateFlow()
    private var editorSelected = false
    private var editorRevision = 0L

    init {
        viewModelScope.launch {
            repository.accounts.collect { accounts ->
                mutableState.value = state.value.copy(accounts = accounts)
                if (!editorSelected && accounts.isNotEmpty()) select(accounts.first())
            }
        }
    }

    fun edit(editor: AccountEditor) {
        if (state.value.busy || editor == state.value.editor) return
        editorSelected = true
        val changedAccount = state.value.editor.id != editor.id
        if (changedAccount) editorRevision++
        mutableState.value = state.value.edited(editor)
    }

    fun select(account: MailAccount) {
        if (state.value.busy) return
        editorSelected = true
        val revision = ++editorRevision
        mutableState.value = state.value.copy(
            editor = AccountEditor(account.id, account.name, account.protocol, account.host, account.port.toString(), account.username, "", account.enabled, serverDeletePolicy = account.serverDeletePolicy),
            credentialLoading = true, saveStatus = AccountOperationStatus.IDLE, testStatus = AccountOperationStatus.IDLE, failureKind = null,
            formRevision = state.value.formRevision + 1, verifiedRevision = null, savedRevision = null, saveFailure = null,
        )
        viewModelScope.launch {
            val hasPassword = try {
                withContext(Dispatchers.IO) { !credentials.load(account.credentialKey).isNullOrEmpty() }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { false }
            if (revision == editorRevision) {
                // Only the credential flag changes; never overwrite operation status or edited fields.
                mutableState.value = state.value.copy(editor = state.value.editor.copy(hasSavedPassword = hasPassword), credentialLoading = false)
            }
        }
    }

    fun save() {
        if (state.value.primaryAction != AccountPrimaryAction.SAVE || !state.value.primaryEnabled) return
        val editor = state.value.editor
        val revision = state.value.formRevision
        editorRevision++
        mutableState.value = state.value.copy(saveStatus = AccountOperationStatus.RUNNING, credentialLoading = false, saveFailure = null)
        viewModelScope.launch {
            try {
                val existing = editor.id?.let { repository.get(it) }
                val account = accountFrom(editor, existing)
                val saved = withContext(Dispatchers.IO) {
                    AccountCredentialSaver(credentials, repository::get, repository::upsert, repository::delete).save(account, editor.password)
                }
                val hasPassword = withContext(Dispatchers.IO) { !credentials.load(saved.credentialKey).isNullOrEmpty() }
                check(hasPassword)
                editorSelected = true
                if (revision == state.value.formRevision) {
                    mutableState.value = state.value.copy(editor = AccountEditor(saved.id, saved.name, saved.protocol, saved.host, saved.port.toString(), saved.username, "", saved.enabled, hasPassword, saved.serverDeletePolicy),
                        saveStatus = AccountOperationStatus.SUCCESS, savedRevision = revision)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision != state.value.formRevision) return@launch
                mutableState.value = state.value.copy(saveStatus = AccountOperationStatus.FAILURE, saveFailure = when (error) {
                    is AccountSaveException -> error.kind
                    is CredentialAccessException -> AccountSaveFailure.CREDENTIAL
                    else -> AccountSaveFailure.ACCOUNT
                })
            }
        }
    }

    fun test() {
        if (state.value.busy || !state.value.editor.canConnect) return
        editorSelected = true
        val editor = state.value.editor
        val revision = state.value.formRevision
        mutableState.value = state.value.copy(testStatus = AccountOperationStatus.RUNNING, failureKind = null,
            verifiedRevision = null, savedRevision = null, saveStatus = AccountOperationStatus.IDLE, saveFailure = null)
        viewModelScope.launch {
            try {
                val existing = editor.id?.let { repository.get(it) }
                val account = accountFrom(editor, existing)
                val password = withContext(Dispatchers.IO) {
                    editor.password.takeIf { it.isNotEmpty() } ?: credentials.load(account.credentialKey)
                } ?: throw MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE)
                val transientCredentials = object : CredentialStore {
                    override fun load(key: String) = password
                    override fun save(key: String, password: String) = error("Read-only test credentials")
                    override fun delete(key: String) = error("Read-only test credentials")
                }
                testConnection(account, transientCredentials)
                if (revision == state.value.formRevision) {
                    mutableState.value = state.value.copy(testStatus = AccountOperationStatus.SUCCESS, verifiedRevision = revision)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision != state.value.formRevision) return@launch
                mutableState.value = state.value.copy(testStatus = AccountOperationStatus.FAILURE, failureKind = (error as? MailReceiverException)?.failureKind ?: MonitoringFailureKind.SERVER_UNAVAILABLE)
            }
        }
    }

    fun delete(account: MailAccount) {
        if (state.value.busy) return
        viewModelScope.launch {
            try {
                repository.delete(account.id)
                withContext(Dispatchers.IO) { credentials.delete(account.credentialKey) }
                if (state.value.editor.id == account.id) {
                    editorRevision++
                    editorSelected = false
                    mutableState.value = state.value.copy(editor = AccountEditor(), credentialLoading = false,
                        formRevision = state.value.formRevision + 1, verifiedRevision = null, savedRevision = null,
                        testStatus = AccountOperationStatus.IDLE, saveStatus = AccountOperationStatus.IDLE,
                        failureKind = null, saveFailure = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saveStatus = AccountOperationStatus.FAILURE)
            }
        }
    }

    private fun accountFrom(editor: AccountEditor, existing: MailAccount?): MailAccount {
        val id = editor.id ?: UUID.randomUUID().toString()
        return MailAccount(id, editor.name.trim(), editor.protocol, editor.host.trim(), editor.port.toInt(), editor.username.trim(), existing?.credentialKey ?: "mail_password_$id", existing?.deleteAfterReceive ?: false, existing?.trustedCertificateId, editor.enabled, editor.serverDeletePolicy)
    }
}
