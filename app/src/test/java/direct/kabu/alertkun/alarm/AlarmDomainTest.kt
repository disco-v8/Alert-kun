package direct.kabu.alertkun.alarm

import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.VibrationPattern
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmDomainTest {
    @Test fun vibrationPatternsMapToDistinctTimings() {
        assertEquals(null, AlarmVibration.timings(VibrationPattern.NONE))
        assertArrayEquals(longArrayOf(0, 300), AlarmVibration.timings(VibrationPattern.PATTERN_1))
        assertArrayEquals(longArrayOf(0, 200, 150, 200), AlarmVibration.timings(VibrationPattern.PATTERN_2))
        assertArrayEquals(longArrayOf(0, 350, 150, 350, 150, 350), AlarmVibration.timings(VibrationPattern.PATTERN_3))
    }

    @Test fun untilStoppedTransitionsToActiveAndStopIsIdempotent() {
        val lifecycle = AlarmLifecycle()
        assertEquals(AlarmState.ACTIVE, lifecycle.start(AlarmMode.UNTIL_STOPPED))
        assertTrue(lifecycle.stop())
        assertEquals(AlarmState.IDLE, lifecycle.state)
        assertFalse(lifecycle.stop())
    }

    @Test fun onceDoesNotCreatePersistentAlarmState() {
        val lifecycle = AlarmLifecycle()
        assertEquals(AlarmState.IDLE, lifecycle.start(AlarmMode.ONCE))
        assertFalse(lifecycle.stop())
    }

    @Test(expected = IllegalArgumentException::class)
    fun eventRejectsBlankRuleId() {
        AlarmEvent("mail", " ", "Rule", null, null, VibrationPattern.NONE, AlarmMode.ONCE)
    }
}
