package direct.kabu.alertkun.monitoring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import direct.kabu.alertkun.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit setup for the disposable emulator only; not part of ordinary test discovery. */
class DemoModeTestPreparation {
    @Test fun prepareDisposableEmulatorForSuite() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val prepareSecrets = arguments.getString("prepareSecretObservation") == "true"
        val cleanSecrets = arguments.getString("cleanupSecretObservation") == "true"
        assumeTrue(arguments.getString("prepareDemoSuite") == "true" || prepareSecrets || cleanSecrets)
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "This setup is for a disposable emulator only"
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(app)
        if (prepareSecrets || cleanSecrets) {
            check(repository.myDnsMasterId.first().isEmpty()) { "Never register a fixture with the real Gateway" }
            val credentials = direct.kabu.alertkun.security.AndroidKeystoreCredentialStore(app)
            val keys = listOf(direct.kabu.alertkun.data.repository.MyDnsSettingsRepository.CREDENTIAL_KEY,
                direct.kabu.alertkun.push.FcmTokenRegistrar.DEVICE_TOKEN_KEY)
            for (key in keys) {
                val current = credentials.load(key)
                check(current == null || current.startsWith("emulator-fixture-")) { "Do not replace existing credentials" }
                if (prepareSecrets) credentials.save(key, "emulator-fixture-${java.util.UUID.randomUUID()}")
                else credentials.delete(key)
            }
            return@runBlocking
        }
        repository.updateDemoModeEnabled(true)
        val settings = repository.settings.first()
        MonitoringDevicePreparation.apply(app, settings, true)
    }
}
