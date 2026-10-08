package direct.kabu.alertkun.push

import direct.kabu.alertkun.data.repository.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged

/** Atomic snapshot of the current primary monitoring settings. */
internal data class FcmRegistrationTarget(val masterId: String, val intervalMinutes: Int,
    val monitoringEnabled: Boolean = false, val revision: Long = 0L) {
    val configured: Boolean get() = masterId.isNotBlank()
}

internal fun SettingsRepository.fcmRegistrationTargets() =
    fcmRegistrationTargets.distinctUntilChanged()
