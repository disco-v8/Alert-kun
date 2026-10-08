package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.Assert.*
import org.junit.Test

class AlarmSessionPolicyTest {
    private val event = AlarmEvent("mail", "rule", "Rule", null, null,
        VibrationPattern.PATTERN_1, AlarmMode.UNTIL_STOPPED)

    @Test fun repeatedHandoffOfSameMailAndRuleIsIgnored() {
        assertTrue(AlarmSessionPolicy.isSameAlarm(event, event.copy()))
    }

    @Test fun newMailReplacesSessionEvenForSameRule() {
        assertFalse(AlarmSessionPolicy.isSameAlarm(event, event.copy(mailId = "new")))
    }

    @Test fun differentRuleIsNewAlarm() {
        assertFalse(AlarmSessionPolicy.isSameAlarm(event, event.copy(ruleId = "other")))
    }

    @Test fun finiteModesNeverClaimContinuousSession() {
        for (mode in listOf(AlarmMode.ONCE, AlarmMode.THREE_TIMES, AlarmMode.FIVE_TIMES)) {
            assertFalse(AlarmSessionPolicy.isSameAlarm(event, event.copy(alarmMode = mode)))
            assertFalse(AlarmSessionPolicy.isSameAlarm(event.copy(alarmMode = mode), event))
        }
    }

    @Test fun absentSessionAllowsStart() {
        assertFalse(AlarmSessionPolicy.isSameAlarm(null, event))
    }
}
