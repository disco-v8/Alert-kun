package direct.kabu.alertkun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import direct.kabu.alertkun.ui.main.AlertKunApp
import direct.kabu.alertkun.ui.theme.AlertKunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlertKunTheme {
                AlertKunApp()
            }
        }
    }
}
