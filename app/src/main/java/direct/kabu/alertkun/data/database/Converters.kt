package direct.kabu.alertkun.data.database

import androidx.room.TypeConverter
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import direct.kabu.alertkun.model.ServerDeletePolicy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter fun fromServerDeleteResolution(value: direct.kabu.alertkun.model.ServerDeleteResolution) = value.name
    @TypeConverter fun toServerDeleteResolution(value: String) = direct.kabu.alertkun.model.ServerDeleteResolution.entries.firstOrNull { it.name == value } ?: direct.kabu.alertkun.model.ServerDeleteResolution.PENDING
    @TypeConverter fun fromTlsValidationMode(value: direct.kabu.alertkun.model.TlsValidationMode) = value.name
    @TypeConverter fun toTlsValidationMode(value: String): direct.kabu.alertkun.model.TlsValidationMode {
        val normalized = if (value == "PINNED_CERTIFICATE") "SELF_SIGNED_ALLOWED" else value
        return direct.kabu.alertkun.model.TlsValidationMode.entries.firstOrNull { it.name == normalized }
            ?: direct.kabu.alertkun.model.TlsValidationMode.STRICT
    }
    @TypeConverter fun fromServerDeletePolicy(value: ServerDeletePolicy) = value.name
    @TypeConverter fun toServerDeletePolicy(value: String) = ServerDeletePolicy.entries.firstOrNull { it.name == value } ?: ServerDeletePolicy.NEVER
    @TypeConverter fun fromSoundType(value: AlarmSoundType) = value.name
    @TypeConverter fun toSoundType(value: String) = AlarmSoundType.valueOf(value)
    @TypeConverter fun fromBackgroundColor(value: MailBackgroundColor) = value.name
    @TypeConverter fun toBackgroundColor(value: String) = MailBackgroundColor.valueOf(value)
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
