package direct.kabu.alertkun.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.monitoring.ObservationExportResult
import direct.kabu.alertkun.monitoring.ObservationLogExporter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ObservationLogExportState(
    val busy: Boolean = false,
    val result: ObservationExportResult? = null,
    val pickerFailed: Boolean = false,
)

internal class ObservationLogExportViewModel(application: Application) : AndroidViewModel(application) {
    private val exporter = ObservationLogExporter(File(application.filesDir, "observability"))
    private val resolver = application.contentResolver
    private val mutableState = MutableStateFlow(ObservationLogExportState())
    val state = mutableState.asStateFlow()

    fun export(uri: Uri?) {
        // Cancelled document selection performs no I/O and preserves the preceding result.
        if (uri == null || state.value.busy) return
        mutableState.value = ObservationLogExportState(busy = true)
        viewModelScope.launch {
            val result = exporter.export {
                resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open export destination")
            }
            mutableState.value = ObservationLogExportState(result = result)
        }
    }

    fun pickerFailed() {
        if (!state.value.busy) mutableState.value = ObservationLogExportState(pickerFailed = true)
    }
}
