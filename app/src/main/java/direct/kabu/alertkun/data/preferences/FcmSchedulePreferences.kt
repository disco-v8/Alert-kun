package direct.kabu.alertkun.data.preferences

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey

/** Primary schedule identity; backup generation also changes on startup and clock rebases. */
internal object FcmSchedulePreferences {
    val revision = longPreferencesKey("fcm_schedule_revision")
    val changedAt = longPreferencesKey("fcm_schedule_changed_at")
    val receiptRevision = longPreferencesKey("fcm_receipt_revision")
    val registrationRevision = longPreferencesKey("fcm_registration_revision")

    fun invalidate(values: MutablePreferences) {
        values[revision] = (values[revision] ?: 0L) + 1L
        values[changedAt] = System.currentTimeMillis()
    }
}
