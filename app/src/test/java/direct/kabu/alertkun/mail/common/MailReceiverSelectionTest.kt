package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import org.junit.Assert.*
import org.junit.Test

class MailReceiverSelectionTest {
    private val account = MailAccount("fixture", "Fixture", MailProtocol.IMAPS, "", 0, "", "unchanged-key", false, null, true)
    private val real = FakeReceiver()
    private val demo = FakeReceiver()

    @Test fun normalWithoutAccountsConstructsNeitherReceiver() {
        val selection = MailReceiverSelection(false, emptyList())
        assertEquals(MailCheckMode.NO_ACCOUNT, selection.mode)
        assertNull(selection.receiver({ error("Real must not be constructed") }, { error("Dummy must not be constructed") }))
    }

    @Test fun disabledAccountsAreNotCheckTargets() {
        assertEquals(MailCheckMode.NO_ACCOUNT, MailReceiverSelection(false, listOf(account.copy(enabled = false))).mode)
    }

    @Test fun normalUsesOnlyEnabledRealAccountsWithoutNewValidationOrMutation() {
        val original = listOf(account, account.copy(id = "disabled", enabled = false))
        val selection = MailReceiverSelection(false, original)
        assertSame(real, selection.receiver({ selected -> assertEquals(listOf(account), selected); real }, { error("No dummy fallback") }))
        assertEquals(MailCheckMode.REAL, selection.mode)
        assertEquals("unchanged-key", original.first().credentialKey)
        assertEquals(2, original.size)
    }

    @Test fun explicitDemoWithoutAccountsUsesOnlyDummy() = assertDemo(emptyList())
    @Test fun explicitDemoWithAccountsStillUsesOnlyDummy() = assertDemo(listOf(account))

    private fun assertDemo(accounts: List<MailAccount>) {
        val selection = MailReceiverSelection(true, accounts)
        assertEquals(MailCheckMode.DEMO, selection.mode)
        assertSame(demo, selection.receiver({ error("Demo must not construct real receiver") }, { demo }))
    }

    private class FakeReceiver : MailReceiver {
        override suspend fun fetchNewMessages() = emptyList<RemoteMail>()
        override suspend fun fetchBody(remoteMail: RemoteMail): String? = null
    }
}
