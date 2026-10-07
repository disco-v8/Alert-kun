package direct.kabu.alertkun.ui.account

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class SavedPasswordMaskTransformationTest {
    @Test fun emptyInputHasFixedDisplayOnlyMaskAndZeroSourceOffsets() {
        val input = AnnotatedString("")
        val transformed = SavedPasswordMaskTransformation("************").filter(input)
        assertEquals("", input.text)
        assertEquals("************", transformed.text.text)
        assertEquals(0, transformed.offsetMapping.originalToTransformed(0))
        for (offset in 0..12) assertEquals(0, transformed.offsetMapping.transformedToOriginal(offset))
    }

    @Test fun realNewInputUsesPasswordMaskInsteadOfSavedDummy() {
        val transformed = SavedPasswordMaskTransformation("************").filter(AnnotatedString("new"))
        assertEquals(3, transformed.text.length)
        assertNotEquals("new", transformed.text.text)
        assertEquals(3, transformed.offsetMapping.originalToTransformed(3))
        assertEquals(3, transformed.offsetMapping.transformedToOriginal(3))
    }
}
