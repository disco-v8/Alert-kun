package direct.kabu.alertkun.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Read-only check of current secrets against retained output. Never prints secret assertion values. */
class GatewaySecretObservationTest {
    @Test fun persistedCredentialsAndFcmTokenAreAbsentFromLogs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = AndroidKeystoreCredentialStore(context)
        val password = credentials.load(MyDnsSettingsRepository.CREDENTIAL_KEY)
        val bearer = credentials.load(FcmTokenRegistrar.DEVICE_TOKEN_KEY)
        assertTrue("Master password remains readable", !password.isNullOrEmpty())
        assertTrue("Device bearer was persisted", !bearer.isNullOrEmpty())
        val fcm = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        val files = File(context.filesDir, "observability").listFiles().orEmpty()
        assertTrue("Observation files exist", files.isNotEmpty())
        val text = files.filter { it.isFile }.joinToString("\n") { it.readText() }
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -v brief")
        val logcat = descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() } }
        listOf(password!!, bearer!!, fcm).forEach { secret ->
            assertFalse("Secret absent from persistent observations", text.contains(secret))
            assertFalse("Secret absent from retained logcat", logcat.contains(secret))
        }
    }
}
