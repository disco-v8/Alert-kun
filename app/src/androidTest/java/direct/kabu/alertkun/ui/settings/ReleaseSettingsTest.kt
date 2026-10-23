package direct.kabu.alertkun.ui.settings

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import direct.kabu.alertkun.MainActivity
import org.junit.Rule
import org.junit.Test

/** Emulator only: reads normal settings without saving, toggling or running checks. */
class ReleaseSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun normalSettingsHaveNoObservationExportUi() {
        compose.onNodeWithTag("main_open_settings").performClick()
        compose.onNodeWithTag("observation_export_save").assertDoesNotExist()
        compose.onNodeWithTag("observation_export_privacy").assertDoesNotExist()
    }
}
