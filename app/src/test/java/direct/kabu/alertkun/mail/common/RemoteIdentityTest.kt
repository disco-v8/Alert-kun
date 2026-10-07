package direct.kabu.alertkun.mail.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteIdentityTest {
    @Test fun pop3UsesUidlVerbatimRatherThanMessageNumber() {
        assertEquals("server-uidl-42", RemoteIdentity.pop3("server-uidl-42"))
    }

    @Test fun imapIdentityIncludesUidValidity() {
        assertEquals("123:42", RemoteIdentity.imap(123, 42))
        assertEquals("124:42", RemoteIdentity.imap(124, 42))
    }
}
