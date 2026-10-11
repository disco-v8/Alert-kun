package direct.kabu.alertkun

import android.app.Application
import direct.kabu.alertkun.monitoring.MonitoringObservation
import direct.kabu.alertkun.monitoring.NetworkRestrictionObserver

class AlertKunApplication : Application() {
    private var networkRestrictionObserver: NetworkRestrictionObserver? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            networkRestrictionObserver = NetworkRestrictionObserver(this) { event, fields ->
                MonitoringObservation.record(this, event, *fields.toList().toTypedArray())
            }.also { it.start() }
        }.onFailure { error ->
            MonitoringObservation.record(this, "NETWORK_RESTRICTION_OBSERVER_START_FAILURE",
                "exceptionClass" to error.javaClass.simpleName)
        }
    }
}
