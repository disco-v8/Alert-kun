package direct.kabu.alertkun.push

/** Selects the supported data action; execution and exclusion remain in the existing worker. */
internal class FcmMailCheckDispatcher(private val enqueueCheck: () -> Unit) {
    fun dispatch(data: Map<String, String>) {
        if (data["action"] == CHECK_MAIL) enqueueCheck()
    }

    companion object {
        const val CHECK_MAIL = "CHECK_MAIL"
    }
}
