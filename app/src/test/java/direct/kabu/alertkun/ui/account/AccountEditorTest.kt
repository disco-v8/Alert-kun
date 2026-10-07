package direct.kabu.alertkun.ui.account

import org.junit.Assert.*
import org.junit.Test

class AccountEditorTest {
    @Test fun disabledReasonsReflectTheSameEditorUsedForSavedPasswordDisplay() {
        val editor = AccountEditor(host = "example.invalid", username = "test", hasSavedPassword = true)
        assertTrue(editor.canConnect)
        assertTrue(editor.connectionIssues.isEmpty())
        assertEquals(listOf(AccountConnectionIssue.PASSWORD), editor.copy(hasSavedPassword = false).connectionIssues)
        assertEquals(listOf(AccountConnectionIssue.HOST), editor.copy(host = "").connectionIssues)
        assertEquals(listOf(AccountConnectionIssue.USERNAME), editor.copy(username = "").connectionIssues)
        assertEquals(listOf(AccountConnectionIssue.PORT), editor.copy(port = "abc").connectionIssues)
        assertEquals(4, AccountEditor(port = "0").connectionIssues.size)
    }
    @Test fun connectionTestDoesNotRequireSavingOrAnAccountName() {
        val editor = AccountEditor(host = "example.invalid", username = "test", password = "test-password")
        assertTrue(editor.canConnect)
        assertFalse(editor.canSave)
        assertNull(editor.id)
    }

    @Test fun editingSavedAccountRequiresAnActuallyAvailableCredential() {
        val editor = AccountEditor(id = "saved-account", name = "Test", host = "example.invalid", username = "test")
        assertFalse(editor.canConnect)
        assertTrue(editor.copy(hasSavedPassword = true).canConnect)
        assertTrue(editor.copy(hasSavedPassword = true).canSave)
    }

    @Test fun invalidPortPreventsSaveAndTest() {
        val editor = AccountEditor(name = "Test", host = "example.invalid", username = "test", password = "test-password", port = "65536")
        assertFalse(editor.canConnect)
        assertFalse(editor.canSave)
    }
}
