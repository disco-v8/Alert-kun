package direct.kabu.alertkun.alarm

import android.content.Context
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.VibrationPattern
import org.json.JSONObject

internal data class PendingAlarm(val token: String, val event: AlarmEvent)

/** One replaceable handoff, never a queue. Contains no credentials or mail body. */
internal class PendingAlarmStore(context: Context) {
    private val preferences = context.getSharedPreferences("pending_alarm", Context.MODE_PRIVATE)

    fun save(alarm: PendingAlarm) {
        val event = alarm.event
        val json = JSONObject().put("token", alarm.token).put("mailId", event.mailId)
            .put("ruleId", event.ruleId).put("ruleName", event.ruleName)
            .put("subject", event.mailSubject).put("sound", event.soundUri)
            .put("soundType", event.soundType.name)
            .put("vibration", event.vibrationPattern.name).put("mode", event.alarmMode.name)
            .put("from", event.mailFrom).put("receivedAt", event.receivedAtEpochMillis)
        check(preferences.edit().putString("event", json.toString()).commit()) { "Alarm handoff persistence failed" }
    }

    fun load(): PendingAlarm? {
        val json = preferences.getString("event", null) ?: return null
        return try {
            val value = JSONObject(json)
            fun optional(key: String) = if (value.has(key) && !value.isNull(key)) value.getString(key) else null
            PendingAlarm(value.getString("token"), AlarmEvent(
                value.getString("mailId"), value.getString("ruleId"), value.getString("ruleName"),
                optional("subject"), optional("sound"), VibrationPattern.valueOf(value.getString("vibration")),
                AlarmMode.valueOf(value.getString("mode")), optional("from"),
                if (value.has("receivedAt") && !value.isNull("receivedAt")) value.getLong("receivedAt") else null,
                AlarmSoundType.valueOf(optional("soundType") ?: AlarmSoundType.ALARM.name),
            ))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        check(preferences.edit().remove("event").commit()) { "Alarm stop persistence failed" }
    }
}
