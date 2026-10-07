package direct.kabu.alertkun.ui.mail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.model.ReceivedMail
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ReceivedMailUiState(
    val mails: List<ReceivedMail> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

class ReceivedMailViewModel @JvmOverloads constructor(
    application: Application,
    repository: MailRepository = MailRepository(AlertKunDatabase.getInstance(application).receivedMailDao()),
) : AndroidViewModel(application) {
    // All persisted mail is visible, including non-alerts and pending recovery.
    val state = repository.mails
        .map { ReceivedMailUiState(mails = it, loading = false) }
        .catch { emit(ReceivedMailUiState(loading = false, loadFailed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceivedMailUiState())
}
