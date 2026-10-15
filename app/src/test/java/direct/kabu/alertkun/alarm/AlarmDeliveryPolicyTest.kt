package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmDeliveryPolicyTest {
    @Test fun finiteModesUseDirectServiceWithoutExactPermissionOrFallBackWhenNotificationsUnavailable() {
        for (mode in AlarmMode.entries.filter { it != AlarmMode.UNTIL_STOPPED })
            for (exact in listOf(false, true)) for (notifications in listOf(false, true)) {
            assertEquals(if (notifications) AlarmDelivery.FOREGROUND_SERVICE else AlarmDelivery.FALLBACK_NOTIFICATION,
                AlarmDeliveryPolicy.delivery(mode, exact, notifications))
        }
    }

    @Test fun continuousAlarmUsesExactAlarmOnlyWithPermissions() {
        assertEquals(AlarmDelivery.EXACT_ALARM, AlarmDeliveryPolicy.delivery(AlarmMode.UNTIL_STOPPED, true, true))
        assertEquals(AlarmDelivery.FALLBACK_NOTIFICATION, AlarmDeliveryPolicy.delivery(AlarmMode.UNTIL_STOPPED, false, true))
        assertEquals(AlarmDelivery.FALLBACK_NOTIFICATION, AlarmDeliveryPolicy.delivery(AlarmMode.UNTIL_STOPPED, true, false))
        assertEquals(AlarmDelivery.FALLBACK_NOTIFICATION, AlarmDeliveryPolicy.delivery(AlarmMode.UNTIL_STOPPED, false, false))
    }
}
