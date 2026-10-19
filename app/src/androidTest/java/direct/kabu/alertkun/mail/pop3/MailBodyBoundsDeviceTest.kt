package direct.kabu.alertkun.mail.pop3

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File

class MailBodyBoundsDeviceTest : MailBodyBoundsContract() {
    override fun temporaryDirectory(): File = ApplicationProvider.getApplicationContext<Context>().cacheDir
}
