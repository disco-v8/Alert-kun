package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount

internal enum class MailCheckMode { NO_ACCOUNT, REAL, DEMO }

/** The enabled flag is the existing check eligibility rule; never validate or rewrite accounts here. */
internal class MailReceiverSelection(demoModeEnabled: Boolean, accounts: List<MailAccount>) {
    val accounts = accounts.filter { it.enabled }
    val mode = when {
        demoModeEnabled -> MailCheckMode.DEMO
        this.accounts.isNotEmpty() -> MailCheckMode.REAL
        else -> MailCheckMode.NO_ACCOUNT
    }

    fun receiver(real: (List<MailAccount>) -> MailReceiver, demo: () -> MailReceiver): MailReceiver? = when (mode) {
        MailCheckMode.NO_ACCOUNT -> null
        MailCheckMode.REAL -> real(accounts)
        MailCheckMode.DEMO -> demo()
    }
}
