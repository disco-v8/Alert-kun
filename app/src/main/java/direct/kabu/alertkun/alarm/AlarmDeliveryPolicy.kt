package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode

enum class AlarmDelivery { ONCE, EXACT_ALARM, ACTIVE_SERVICE, FALLBACK_ONCE }

/** A persistent alarm must have both an automatic-start route and a visible Stop action. */
object AlarmDeliveryPolicy {
    fun delivery(mode: AlarmMode, exactAllowed: Boolean, notificationsAllowed: Boolean): AlarmDelivery =
        when {
            mode != AlarmMode.UNTIL_STOPPED -> AlarmDelivery.ONCE
            exactAllowed && notificationsAllowed -> AlarmDelivery.EXACT_ALARM
            else -> AlarmDelivery.FALLBACK_ONCE
        }
}
