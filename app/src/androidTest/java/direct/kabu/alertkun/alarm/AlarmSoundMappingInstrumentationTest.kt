package direct.kabu.alertkun.alarm

import android.content.Context
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.os.Build
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlarmSoundType
import direct.kabu.alertkun.model.VibrationPattern
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlarmSoundMappingInstrumentationTest {
    @Test fun playbackAttributesUseSelectedUsageAndMatchingVolumeStream() {
        for (type in AlarmSoundType.entries) {
            val attributes = AlarmSoundMapping.audioAttributes(type)
            val expected = when (type) {
                AlarmSoundType.ALARM -> AudioAttributes.USAGE_ALARM
                AlarmSoundType.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                AlarmSoundType.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            }
            assertEquals(expected, attributes.usage)
            assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(AlarmSoundMapping.legacyStreamType(type), attributes.volumeControlStream)
            }
        }
    }

    @Test fun pickerAndDefaultsUseEachCategoryAndExplicitUriWins() {
        for (type in AlarmSoundType.entries) {
            val expected = RingtoneManager.getDefaultUri(AlarmSoundMapping.ringtoneType(type))
            assertEquals(expected, AlarmSoundMapping.resolveUri(type, null))
            val selected = "content://sounds/selected"
            assertEquals(Uri.parse(selected), AlarmSoundMapping.resolveUri(type, selected))
            val intent = AlarmSoundMapping.pickerIntent(type, null)
            assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
            assertEquals(AlarmSoundMapping.ringtoneType(type), intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
            @Suppress("DEPRECATION")
            assertEquals(expected, intent.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI))
            @Suppress("DEPRECATION")
            assertEquals(expected, intent.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
            assertFalse(intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        }
    }

    @Test fun handoffPersistsSoundTypeAndOldHandoffDefaultsToAlarmWithoutChangingUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PendingAlarmStore(context)
        try {
            for (type in AlarmSoundType.entries) {
                val alarm = PendingAlarm("sound-test", AlarmEvent("mail", "rule", "Rule", null,
                    "content://sounds/existing", VibrationPattern.NONE, AlarmMode.UNTIL_STOPPED, soundType = type))
                store.save(alarm)
                assertEquals(alarm, PendingAlarmStore(context).load())
            }
            val preferences = context.getSharedPreferences("pending_alarm", Context.MODE_PRIVATE)
            val legacy = JSONObject(preferences.getString("event", null)!!).apply { remove("soundType") }
            assertTrue(preferences.edit().putString("event", legacy.toString()).commit())
            val recovered = store.load()!!
            assertEquals(AlarmSoundType.ALARM, recovered.event.soundType)
            assertEquals("content://sounds/existing", recovered.event.soundUri)
        } finally { store.clear() }
    }
}
