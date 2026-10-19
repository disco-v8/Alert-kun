package direct.kabu.alertkun.mail.imap

class ImapReceiveDeviceTest : ImapReceiveContract() {
    override val temporaryDirectory: java.io.File
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
}
