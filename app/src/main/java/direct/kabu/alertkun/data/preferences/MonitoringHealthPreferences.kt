package direct.kabu.alertkun.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey

/** Shared keys so setting changes and notification claims use the same atomic DataStore edit. */
internal object MonitoringHealthPreferences {
    val enabled = booleanPreferencesKey("monitoring_health_notification_enabled")
    val observingSince = longPreferencesKey("monitoring_health_observing_since")
    val lastNotificationAt = longPreferencesKey("monitoring_health_last_notification_at")
    val lastFailedCheckAt = longPreferencesKey("monitoring_health_last_failed_check_at")
}
