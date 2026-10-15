package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode

enum class AlarmDelivery { FOREGROUND_SERVICE, EXACT_ALARM, ACTIVE_SERVICE, FALLBACK_NOTIFICATION }

/** A persistent alarm must have both an automatic-start route and a visible Stop action. */
object AlarmDeliveryPolicy {
    fun delivery(mode: AlarmMode, exactAllowed: Boolean, notificationsAllowed: Boolean): AlarmDelivery =
        when {
            mode != AlarmMode.UNTIL_STOPPED && notificationsAllowed -> AlarmDelivery.FOREGROUND_SERVICE
            mode == AlarmMode.UNTIL_STOPPED && exactAllowed && notificationsAllowed -> AlarmDelivery.EXACT_ALARM
            else -> AlarmDelivery.FALLBACK_NOTIFICATION
        }
}
