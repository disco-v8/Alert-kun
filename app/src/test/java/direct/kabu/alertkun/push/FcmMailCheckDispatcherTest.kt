package direct.kabu.alertkun.push

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmMailCheckDispatcherTest {
    @Test fun verificationIsConsumedBeforeStatusAndMailPaths() {
        var verification = 0
        var checks = 0
        var statusUpdates = 0
        fun receive(data: Map<String, String>) {
            if (dispatchRegistrationVerification(data) { verification++ }) return
            statusUpdates++
            FcmMailCheckDispatcher { checks++ }.dispatch(data)
        }
        receive(mapOf("action" to "VERIFY_REGISTRATION"))
        assertEquals(1, verification)
        assertEquals(0, statusUpdates)
        assertEquals(0, checks)
        receive(mapOf("action" to "CHECK_MAIL"))
        assertEquals(1, checks)
        assertEquals(1, statusUpdates)
    }
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
