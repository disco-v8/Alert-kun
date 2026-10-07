package direct.kabu.alertkun.ui.account

import direct.kabu.alertkun.model.MailProtocol
import org.junit.Assert.*
import org.junit.Test

class AccountPrimaryActionTest {
    private val editor = AccountEditor(name = "Test", host = "example.invalid", username = "test", password = "test-password")
    private val verified = AccountSettingsState(editor = editor, formRevision = 10,
        verifiedRevision = 10, testStatus = AccountOperationStatus.SUCCESS)

    @Test fun insufficientAndCompleteFormsOfferOnlyTest() {
        assertEquals(AccountPrimaryAction.TEST, AccountSettingsState().primaryAction)
        assertFalse(AccountSettingsState().primaryEnabled)
        assertTrue(AccountSettingsState(editor = editor).primaryEnabled)
        assertTrue(AccountSettingsState(editor = editor.copy(password = "", hasSavedPassword = true)).primaryEnabled)
        assertFalse(AccountSettingsState(editor = editor.copy(password = "")).primaryEnabled)
    }

    @Test fun primaryActionCoversRunningVerifiedSavingSavedAndFailureStates() {
        assertEquals(AccountPrimaryAction.TESTING, verified.copy(testStatus = AccountOperationStatus.RUNNING).primaryAction)
        assertFalse(verified.copy(testStatus = AccountOperationStatus.RUNNING).primaryEnabled)
        assertEquals(AccountPrimaryAction.SAVE, verified.primaryAction)
        assertTrue(verified.primaryEnabled)
        assertEquals(AccountPrimaryAction.SAVING, verified.copy(saveStatus = AccountOperationStatus.RUNNING).primaryAction)
        assertFalse(verified.copy(saveStatus = AccountOperationStatus.RUNNING).primaryEnabled)
        val saved = verified.copy(saveStatus = AccountOperationStatus.SUCCESS, savedRevision = 10)
        assertEquals(AccountPrimaryAction.SAVED, saved.primaryAction)
        assertFalse(saved.primaryEnabled)
        assertEquals(AccountPrimaryAction.SAVE, verified.copy(saveStatus = AccountOperationStatus.FAILURE).primaryAction)
        assertTrue(verified.copy(saveStatus = AccountOperationStatus.FAILURE).primaryEnabled)
    }

    @Test fun everyUserEditableFieldInvalidatesVerificationAndSavedState() {
        val changes = listOf(editor.copy(host = "other.invalid"), editor.copy(port = "993"),
            editor.copy(username = "other"), editor.copy(protocol = MailProtocol.IMAPS),
            editor.copy(password = "new-test-password"), editor.copy(name = "Other"), editor.copy(enabled = false),
            editor.copy(serverDeletePolicy = direct.kabu.alertkun.model.ServerDeletePolicy.IMMEDIATELY))
        changes.forEach { changed ->
            listOf(verified, verified.copy(saveStatus = AccountOperationStatus.SUCCESS, savedRevision = 10)).forEach { before ->
                val after = before.edited(changed)
                assertEquals(11L, after.formRevision)
                assertNull(after.verifiedRevision)
                assertNull(after.savedRevision)
                assertEquals(AccountOperationStatus.IDLE, after.testStatus)
                assertEquals(AccountOperationStatus.IDLE, after.saveStatus)
                assertEquals(AccountPrimaryAction.TEST, after.primaryAction)
            }
        }
    }

    @Test fun staleRevisionIsNeverSaveableAndNoOpEditKeepsCurrentVerification() {
        assertEquals(AccountPrimaryAction.TEST, verified.copy(formRevision = 11).primaryAction)
        assertEquals(verified, verified.edited(editor))
        assertFalse(verified.copy(editor = editor.copy(name = "")).primaryEnabled)
    }
}
