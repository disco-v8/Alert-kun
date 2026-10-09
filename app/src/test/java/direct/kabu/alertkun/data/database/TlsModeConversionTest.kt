package direct.kabu.alertkun.data.database

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.TlsValidationMode
import org.junit.Assert.*
import org.junit.Test

class TlsModeConversionTest {
    @Test fun legacyPinnedValueReadsAsSelfSignedAndWritesNormalizedValue() {
        val converters = Converters()
        val mode = converters.toTlsValidationMode("PINNED_CERTIFICATE")
        assertEquals(TlsValidationMode.SELF_SIGNED_ALLOWED, mode)
        assertEquals("SELF_SIGNED_ALLOWED", converters.fromTlsValidationMode(mode))
    }
    @Test fun allFourModesRoundTripWithExistingStorageNames() {
        val converter = Converters()
        TlsValidationMode.entries.forEach { assertEquals(it, converter.toTlsValidationMode(converter.fromTlsValidationMode(it))) }
        assertEquals(TlsValidationMode.CA_VALIDATED_NO_HOSTNAME, converter.toTlsValidationMode("CA_VALIDATED_NO_HOSTNAME"))
        assertEquals(TlsValidationMode.STRICT, converter.toTlsValidationMode("unknown"))
        assertEquals(TlsValidationMode.STRICT, converter.toTlsValidationMode(""))
    }
    @Test fun newAccountDefaultsToStrict() {
        val account = MailAccount("a", "Fixture", MailProtocol.POP3S, "example.invalid", 995,
            "fixture", "fixture-key", false, null, true)
        assertEquals(TlsValidationMode.STRICT, account.tlsValidationMode)
    }
}
