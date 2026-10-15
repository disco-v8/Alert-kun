package direct.kabu.alertkun

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import direct.kabu.alertkun.alarm.AlarmController
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import direct.kabu.alertkun.ui.main.AlertKunApp
import direct.kabu.alertkun.ui.theme.AlertKunTheme
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import direct.kabu.alertkun.push.FcmRegistrationCoordinator

class MainActivity : AppCompatActivity() {
    private var openMailRequest by mutableIntStateOf(0)
    private var openMainRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FcmRegistrationCoordinator.start(applicationContext)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        enableEdgeToEdge()
        handleNavigationIntent(intent)
        lifecycleScope.launch {
            MonitoringScheduler.reconcileOnAppStart(this@MainActivity, SettingsRepository(this@MainActivity).settings.first())
        }
        setContent {
            AlertKunTheme {
                AlertKunApp(openMailRequest, openMainRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        val nextRequest = maxOf(openMailRequest, openMainRequest) + 1
        when (intent.action) {
            AlarmController.ACTION_OPEN_MAIL -> openMailRequest = nextRequest
            ACTION_OPEN_MAIN -> openMainRequest = nextRequest
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmController.getInstance(this).refreshPendingPermissionWarning()
    }

    companion object {
        const val ACTION_OPEN_MAIN = "direct.kabu.alertkun.action.OPEN_MAIN"
    }
}
