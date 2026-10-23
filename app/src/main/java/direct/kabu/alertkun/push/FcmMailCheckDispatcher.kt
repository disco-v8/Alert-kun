package direct.kabu.alertkun.push

/** Consumes confirmation messages before any mail-check/notification observation path. */
internal fun dispatchRegistrationVerification(data: Map<String, String>, receive: (Map<String, String>) -> Unit): Boolean {
    if (data["action"] != PendingRegistration.VERIFY) return false
    receive(data)
    return true
}

/** Selects the supported data action; execution and exclusion remain in the existing worker. */
internal class FcmMailCheckDispatcher(private val enqueueCheck: () -> Unit) {
    fun dispatch(data: Map<String, String>) {
        if (data["action"] == CHECK_MAIL) enqueueCheck()
    }

    companion object {
        const val CHECK_MAIL = "CHECK_MAIL"
    }
}
