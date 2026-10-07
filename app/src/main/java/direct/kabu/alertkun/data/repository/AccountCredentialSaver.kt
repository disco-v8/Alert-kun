package direct.kabu.alertkun.data.repository

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class AccountSaveFailure { ACCOUNT, CREDENTIAL, ROLLBACK }

class AccountSaveException(val kind: AccountSaveFailure) : Exception("Account save failed")

/** Room and Keystore cannot share a transaction. Stage disabled, verify, then publish. */
class AccountCredentialSaver(
    private val credentials: CredentialStore,
    private val get: suspend (String) -> MailAccount?,
    private val upsert: suspend (MailAccount) -> Unit,
    private val delete: suspend (String) -> Unit,
) {
    // Caller runs this bounded persistence operation on IO.
    suspend fun save(account: MailAccount, password: String): MailAccount {
        val previous = get(account.id)
        var kind = AccountSaveFailure.CREDENTIAL
        var previousCredentialUnreadable = false
        val previousPassword = try {
            credentials.load(account.credentialKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (password.isEmpty()) throw AccountSaveException(kind)
            // A newly entered password can repair an unreadable old credential.
            // If that replacement fails, do not claim the old secret was restored.
            previousCredentialUnreadable = true
            null
        }
        var staged = false
        var credentialWritten = false
        try {
            kind = AccountSaveFailure.ACCOUNT
            staged = true
            upsert(account.copy(enabled = false))
            val persisted = checkNotNull(get(account.id))
            check(persisted.id == account.id && persisted.credentialKey == account.credentialKey)
            kind = AccountSaveFailure.CREDENTIAL
            if (password.isNotEmpty()) {
                credentialWritten = true
                credentials.save(persisted.credentialKey, password)
            }
            val loaded = credentials.load(persisted.credentialKey)
            check(!loaded.isNullOrEmpty())
            check(password.isEmpty() || loaded == password)
            kind = AccountSaveFailure.ACCOUNT
            upsert(account)
            val saved = checkNotNull(get(account.id))
            kind = AccountSaveFailure.CREDENTIAL
            check(!credentials.load(saved.credentialKey).isNullOrEmpty())
            return saved
        } catch (error: Exception) {
            var rollbackFailed = false
            withContext(NonCancellable) {
                try {
                    if (credentialWritten) {
                        check(!previousCredentialUnreadable)
                        if (previousPassword == null) credentials.delete(account.credentialKey)
                        else credentials.save(account.credentialKey, previousPassword)
                    }
                    if (staged) {
                        if (previous == null) delete(account.id) else upsert(previous)
                    }
                } catch (_: Exception) {
                    // Leave staged account disabled if recovery cannot complete.
                    rollbackFailed = true
                    try { upsert(account.copy(enabled = false)) } catch (_: Exception) { /* Report below. */ }
                }
            }
            if (error is CancellationException) throw error
            throw AccountSaveException(if (rollbackFailed) AccountSaveFailure.ROLLBACK else kind)
        }
    }
}
