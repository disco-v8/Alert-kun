package direct.kabu.alertkun.data.database

import androidx.room.TypeConverter
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json
    @TypeConverter fun fromMailProtocol(value: MailProtocol) = value.name
    @TypeConverter fun toMailProtocol(value: String) = MailProtocol.valueOf(value)
    @TypeConverter fun fromRuleTarget(value: RuleTarget) = value.name
    @TypeConverter fun toRuleTarget(value: String) = RuleTarget.valueOf(value)
    @TypeConverter fun fromAlarmMode(value: AlarmMode) = value.name
    @TypeConverter fun toAlarmMode(value: String) = AlarmMode.valueOf(value)
    @TypeConverter fun fromVibrationPattern(value: VibrationPattern) = value.name
    @TypeConverter fun toVibrationPattern(value: String) = VibrationPattern.valueOf(value)
    @TypeConverter fun fromProcessingState(value: MailProcessingState) = value.name
    @TypeConverter fun toProcessingState(value: String) = MailProcessingState.valueOf(value)
    @TypeConverter fun fromHeaders(value: Map<String, String>): String = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), value)
    @TypeConverter fun toHeaders(value: String): Map<String, String> = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), value)
}
