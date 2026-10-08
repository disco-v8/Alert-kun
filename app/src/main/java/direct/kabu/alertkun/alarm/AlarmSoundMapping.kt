package direct.kabu.alertkun.alarm

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import direct.kabu.alertkun.model.AlarmSoundType

/** Keeps Android sound selection, playback usage and legacy focus streams aligned. */
internal object AlarmSoundMapping {
    fun usage(type: AlarmSoundType): Int = when (type) {
        AlarmSoundType.ALARM -> AudioAttributes.USAGE_ALARM
        AlarmSoundType.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
        AlarmSoundType.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    }

    fun audioAttributes(type: AlarmSoundType): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage(type)).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** Continuous alarms keep the chosen URI but use alarm routing for Android 17's exact-alarm exemption. */
    fun continuousAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun legacyStreamType(type: AlarmSoundType): Int = when (type) {
        AlarmSoundType.ALARM -> AudioManager.STREAM_ALARM
        AlarmSoundType.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AlarmSoundType.RINGTONE -> AudioManager.STREAM_RING
    }

    fun ringtoneType(type: AlarmSoundType): Int = when (type) {
        AlarmSoundType.ALARM -> RingtoneManager.TYPE_ALARM
        AlarmSoundType.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
        AlarmSoundType.RINGTONE -> RingtoneManager.TYPE_RINGTONE
    }

    fun defaultUri(type: AlarmSoundType): Uri = RingtoneManager.getDefaultUri(ringtoneType(type))

    fun resolveUri(type: AlarmSoundType, soundUri: String?): Uri =
        soundUri?.let(Uri::parse) ?: defaultUri(type)

    fun pickerIntent(type: AlarmSoundType, soundUri: String?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, ringtoneType(type))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri(type))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, resolveUri(type, soundUri))
}
