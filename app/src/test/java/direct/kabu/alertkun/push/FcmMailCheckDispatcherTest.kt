package direct.kabu.alertkun.push

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmMailCheckDispatcherTest {
    @Test fun checkMailEnqueuesExactlyOneCheck() {
        var checks = 0
        FcmMailCheckDispatcher { checks++ }.dispatch(mapOf("action" to "CHECK_MAIL"))
        assertEquals(1, checks)
    }

    @Test fun unknownOrMissingActionsDoNotEnqueue() {
        var checks = 0
        val dispatcher = FcmMailCheckDispatcher { checks++ }
        listOf(emptyMap(), mapOf("other" to "CHECK_MAIL"), mapOf("action" to ""),
            mapOf("action" to "UNKNOWN"), mapOf("action" to "check_mail"),
            mapOf("action" to " CHECK_MAIL ")).forEach(dispatcher::dispatch)
        assertEquals(0, checks)
    }
}
