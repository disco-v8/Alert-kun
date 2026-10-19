package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType

enum class AlarmDelivery { FOREGROUND_SERVICE, EXACT_ALARM, ACTIVE_SERVICE, FALLBACK_NOTIFICATION }

/** Selects the entry only; playback counts and active-service replacement are unchanged. */
object AlarmDeliveryPolicy {
    fun delivery(mode: AlarmMode, exactAllowed: Boolean, notificationsAllowed: Boolean,
        soundType: AlarmSoundType, sdkInt: Int): AlarmDelivery =
        when {
            acceptsExactEntry(mode, soundType, sdkInt) && exactAllowed && notificationsAllowed -> AlarmDelivery.EXACT_ALARM
            mode != AlarmMode.UNTIL_STOPPED && notificationsAllowed -> AlarmDelivery.FOREGROUND_SERVICE
            else -> AlarmDelivery.FALLBACK_NOTIFICATION
        }

    fun acceptsExactEntry(mode: AlarmMode, soundType: AlarmSoundType, sdkInt: Int): Boolean =
        mode == AlarmMode.UNTIL_STOPPED ||
            (sdkInt >= 37 && mode.finiteCount != null && soundType == AlarmSoundType.ALARM)
}
