package direct.kabu.alertkun.model

import direct.kabu.alertkun.data.database.Converters
import direct.kabu.alertkun.data.entity.toDomain
import direct.kabu.alertkun.data.entity.toEntity
import direct.kabu.alertkun.ui.rule.RuleEditUiState
import org.junit.Assert.*
import org.junit.Test

class AlarmSoundTypeTest {
    private val rule = AlertRule("r", "Rule", "WARN", RuleTarget.SUBJECT, 1,
        "content://sounds/old", VibrationPattern.NONE, AlarmMode.ONCE, true)

    @Test fun newRuleDefaultsToAlarm() {
        assertEquals(AlarmSoundType.ALARM, rule.soundType)
        assertEquals(AlarmSoundType.ALARM, RuleEditUiState.newRule().soundType)
    }

    @Test fun allTypesRoundTripThroughStableNamesAndEntity() {
        val converters = Converters()
        for (type in AlarmSoundType.entries) {
            assertEquals(type.name, converters.fromSoundType(type))
            assertEquals(type, converters.toSoundType(type.name))
            val updated = rule.copy(soundType = type)
            assertEquals(updated, updated.toEntity().toDomain())
        }
    }

    @Test fun changingCategoryClearsOldUriAndReselectingSameCategoryPreservesIt() {
        for (oldType in AlarmSoundType.entries) {
            val original = rule.copy(soundType = oldType)
            assertSame(original, original.withSoundType(oldType))
            for (newType in AlarmSoundType.entries.filter { it != oldType }) {
                assertEquals(original.copy(soundType = newType, soundUri = null), original.withSoundType(newType))
            }
        }
    }
}
