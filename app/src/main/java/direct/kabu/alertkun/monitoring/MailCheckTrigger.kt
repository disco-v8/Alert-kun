package direct.kabu.alertkun.monitoring

enum class MailCheckTrigger {
    FCM, SCHEDULED_WORK, MANUAL, STARTUP;

    companion object {
        fun fromInput(value: String?, scheduled: Boolean, startup: Boolean): MailCheckTrigger = when {
            startup && scheduled -> STARTUP
            scheduled -> SCHEDULED_WORK
            value == FCM.name -> FCM
            else -> MANUAL
        }
    }
}
