package direct.kabu.alertkun

import android.os.Bundle
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

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        enableEdgeToEdge()
        lifecycleScope.launch {
            MonitoringScheduler.reconcileOnAppStart(this@MainActivity, SettingsRepository(this@MainActivity).settings.first())
        }
        setContent {
            AlertKunTheme {
                AlertKunApp()
            }
        }
    }
}
