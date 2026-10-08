package direct.kabu.alertkun.data.repository

import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class MyDnsSaveFailure { STORAGE, ROLLBACK }
class MyDnsSaveException(val kind: MyDnsSaveFailure) : Exception("Unable to save MyDNS settings")
data class MyDnsSavedSettings(val masterId: String, val passwordSaved: Boolean)

/** Passwords never enter normal settings. Save/verify the credential before publishing the ID. */
class MyDnsSettingsRepository internal constructor(
    private val credentials: CredentialStore,
    private val readMasterId: suspend () -> String,
    private val writeMasterId: suspend (String) -> Unit,
) {
    constructor(settings: SettingsRepository, credentials: CredentialStore) : this(
        credentials, { settings.myDnsMasterId.first() }, settings::updateMyDnsMasterId,
    )

    suspend fun read(): MyDnsSavedSettings = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            MyDnsSavedSettings(readMasterId(), !credentials.load(CREDENTIAL_KEY).isNullOrEmpty())
        }
    }

    suspend fun save(masterId: String, newPassword: String): MyDnsSavedSettings =
        withContext(Dispatchers.IO) {
            saveMutex.withLock {
                // Keep the bounded two-store save and any compensation together on cancellation.
                withContext(NonCancellable) {
                    val previousId = readMasterId()
                    var previousUnreadable = false
                    val previousPassword = try {
                        credentials.load(CREDENTIAL_KEY)
                    } catch (_: Exception) {
                        if (newPassword.isEmpty()) throw MyDnsSaveException(MyDnsSaveFailure.STORAGE)
                        previousUnreadable = true
                        null
                    }
                    var passwordWriteAttempted = false
                    var idWriteAttempted = false
                    try {
                        if (newPassword.isNotEmpty()) {
                            passwordWriteAttempted = true
                            credentials.save(CREDENTIAL_KEY, newPassword)
                        }
                        val loaded = credentials.load(CREDENTIAL_KEY)
                        check(newPassword.isEmpty() || loaded == newPassword)
                        idWriteAttempted = true
                        writeMasterId(masterId.trim())
                        MyDnsSavedSettings(masterId.trim(), !loaded.isNullOrEmpty())
                    } catch (_: Exception) {
                        var rollbackFailed = false
                        if (passwordWriteAttempted) {
                            try {
                                check(!previousUnreadable)
                                if (previousPassword == null) credentials.delete(CREDENTIAL_KEY)
                                else credentials.save(CREDENTIAL_KEY, previousPassword)
                                check(credentials.load(CREDENTIAL_KEY) == previousPassword)
                            } catch (_: Exception) { rollbackFailed = true }
                        }
                        if (idWriteAttempted) {
                            try { writeMasterId(previousId); check(readMasterId() == previousId) }
                            catch (_: Exception) { rollbackFailed = true }
                        }
                        throw MyDnsSaveException(if (rollbackFailed) MyDnsSaveFailure.ROLLBACK else MyDnsSaveFailure.STORAGE)
                    }
                }
            }
        }

    companion object {
        const val CREDENTIAL_KEY = "mydns_master_password_v1"
        private val saveMutex = Mutex()
    }
}
