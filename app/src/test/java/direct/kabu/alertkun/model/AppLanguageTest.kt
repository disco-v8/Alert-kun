package direct.kabu.alertkun.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun englishIsTheDefaultLanguage() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.entries.first())
    }
}
