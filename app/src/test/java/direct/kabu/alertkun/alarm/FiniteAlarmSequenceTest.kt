package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import direct.kabu.alertkun.data.database.Converters
import org.junit.Assert.*
import org.junit.Test

class FiniteAlarmSequenceTest {
    @Test fun finiteModesCountExactlyOneThreeAndFiveWholeCycles() {
        for ((mode, count) in listOf(AlarmMode.ONCE to 1, AlarmMode.THREE_TIMES to 3, AlarmMode.FIVE_TIMES to 5)) {
            assertEquals(count, mode.finiteCount)
            val sequence = FiniteAlarmSequence(count)
            repeat(count) { completed ->
                assertEquals(count - completed, sequence.remainingCount)
                assertEquals(completed < count - 1, sequence.completeCycle())
            }
            assertEquals(0, sequence.remainingCount)
            assertFalse(sequence.completeCycle())
        }
        assertNull(AlarmMode.UNTIL_STOPPED.finiteCount)
    }

    @Test fun cancellationDiscardsRemainingCycles() {
        val sequence = FiniteAlarmSequence(5)
        assertTrue(sequence.completeCycle())
        sequence.cancel()
        assertEquals(0, sequence.remainingCount)
        assertFalse(sequence.completeCycle())
    }

    @Test fun newRulesDefaultToOnceAndExistingStoredNamesRemainCompatible() {
        val rule = AlertRule("r", "Rule", "WARN", RuleTarget.SUBJECT, 1, null, VibrationPattern.NONE, enabled = true)
        assertEquals(AlarmMode.ONCE, rule.alarmMode)
        val converters = Converters()
        assertEquals(AlarmMode.ONCE, converters.toAlarmMode("ONCE"))
        assertEquals(AlarmMode.UNTIL_STOPPED, converters.toAlarmMode("UNTIL_STOPPED"))
        for (mode in AlarmMode.entries) assertEquals(mode, converters.toAlarmMode(converters.fromAlarmMode(mode)))
    }
}
