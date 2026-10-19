package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import org.junit.Assert.*
import org.junit.Test

class AlarmDeliveryPolicyTest {
    private val finite = listOf(AlarmMode.ONCE, AlarmMode.THREE_TIMES, AlarmMode.FIVE_TIMES)

    @Test fun android17AndLaterFiniteAlarmUsesExactEntry() {
        for (sdk in listOf(37, 38)) for (mode in finite) {
            assertEquals(AlarmDelivery.EXACT_ALARM, delivery(mode, sdk = sdk))
            assertTrue(AlarmDeliveryPolicy.acceptsExactEntry(mode, AlarmSoundType.ALARM, sdk))
        }
    }

    @Test fun android26Through36FiniteAlarmKeepsDirectEntry() {
        for (sdk in 26..36) for (mode in finite) for (exact in listOf(false, true)) {
            assertEquals(AlarmDelivery.FOREGROUND_SERVICE, delivery(mode, sdk = sdk, exact = exact))
            assertFalse(AlarmDeliveryPolicy.acceptsExactEntry(mode, AlarmSoundType.ALARM, sdk))
        }
    }

    @Test fun android17NotificationAndRingtoneKeepDirectEntry() {
        for (type in listOf(AlarmSoundType.NOTIFICATION, AlarmSoundType.RINGTONE)) for (mode in finite) {
            assertEquals(AlarmDelivery.FOREGROUND_SERVICE, delivery(mode, type = type))
            assertFalse(AlarmDeliveryPolicy.acceptsExactEntry(mode, type, 37))
        }
    }

    @Test fun finiteWithoutExactPermissionStillUsesDirectService() {
        for (mode in finite) for (type in AlarmSoundType.entries) {
            assertEquals(AlarmDelivery.FOREGROUND_SERVICE, delivery(mode, type = type, exact = false))
        }
    }

    @Test fun notificationsUnavailablePreservesFallbackForEveryModeAndSound() {
        for (sdk in listOf(26, 36, 37)) for (mode in AlarmMode.entries)
            for (type in AlarmSoundType.entries) for (exact in listOf(false, true)) {
                assertEquals(AlarmDelivery.FALLBACK_NOTIFICATION,
                    delivery(mode, type, sdk, exact, notifications = false))
            }
    }

    @Test fun continuousAlarmKeepsExistingPermissionSemanticsOnEveryApi() {
        for (sdk in listOf(26, 36, 37)) for (type in AlarmSoundType.entries) {
            assertEquals(AlarmDelivery.EXACT_ALARM, delivery(AlarmMode.UNTIL_STOPPED, type, sdk))
            assertEquals(AlarmDelivery.FALLBACK_NOTIFICATION,
                delivery(AlarmMode.UNTIL_STOPPED, type, sdk, exact = false))
            assertTrue(AlarmDeliveryPolicy.acceptsExactEntry(AlarmMode.UNTIL_STOPPED, type, sdk))
        }
    }

    private fun delivery(mode: AlarmMode, type: AlarmSoundType = AlarmSoundType.ALARM, sdk: Int = 37,
        exact: Boolean = true, notifications: Boolean = true) =
        AlarmDeliveryPolicy.delivery(mode, exact, notifications, type, sdk)
}
