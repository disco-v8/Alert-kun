package direct.kabu.alertkun

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import direct.kabu.alertkun.ui.main.AlertKunApp
import direct.kabu.alertkun.ui.theme.AlertKunTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        enableEdgeToEdge()
        setContent {
            AlertKunTheme {
                AlertKunApp()
            }
        }
    }
}
