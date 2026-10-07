package direct.kabu.alertkun.alarm

import android.media.RingtoneManager
import android.media.AudioAttributes
import android.media.AudioManager
import direct.kabu.alertkun.model.AlarmSoundType
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSoundMappingTest {
    @Test fun categoriesMapToIndividualPlaybackUsages() {
        assertEquals(AudioAttributes.USAGE_ALARM, AlarmSoundMapping.usage(AlarmSoundType.ALARM))
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, AlarmSoundMapping.usage(AlarmSoundType.NOTIFICATION))
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AlarmSoundMapping.usage(AlarmSoundType.RINGTONE))
    }

    @Test fun legacyFocusStreamsMatchPlaybackCategories() {
        assertEquals(AudioManager.STREAM_ALARM, AlarmSoundMapping.legacyStreamType(AlarmSoundType.ALARM))
        assertEquals(AudioManager.STREAM_NOTIFICATION, AlarmSoundMapping.legacyStreamType(AlarmSoundType.NOTIFICATION))
        assertEquals(AudioManager.STREAM_RING, AlarmSoundMapping.legacyStreamType(AlarmSoundType.RINGTONE))
    }

    @Test fun categoriesMapToIndividualAndroidLists() {
        assertEquals(RingtoneManager.TYPE_ALARM, AlarmSoundMapping.ringtoneType(AlarmSoundType.ALARM))
        assertEquals(RingtoneManager.TYPE_NOTIFICATION, AlarmSoundMapping.ringtoneType(AlarmSoundType.NOTIFICATION))
        assertEquals(RingtoneManager.TYPE_RINGTONE, AlarmSoundMapping.ringtoneType(AlarmSoundType.RINGTONE))
    }
}
