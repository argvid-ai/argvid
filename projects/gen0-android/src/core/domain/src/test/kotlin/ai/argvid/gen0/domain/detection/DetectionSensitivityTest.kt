package ai.argvid.gen0.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionSensitivityTest {
    @Test
    fun progressIsClampedAndDefaultsToFifty() {
        assertEquals(50, DetectionSensitivity.DEFAULT.progress)
        assertEquals(0, DetectionSensitivity.fromProgress(-10).progress)
        assertEquals(100, DetectionSensitivity.fromProgress(110).progress)
    }

    @Test
    fun higherSensitivityAcceptsSmallerAndLowerConfidencePeople() {
        val strict = DetectionSensitivity.fromProgress(0)
        val permissive = DetectionSensitivity.fromProgress(100)

        assertEquals(0.80f, strict.minimumPersonConfidence, 0.0001f)
        assertEquals(0.50f, permissive.minimumPersonConfidence, 0.0001f)
        assertEquals(0.08f, strict.minimumPersonAreaRatio, 0.0001f)
        assertEquals(0.01f, permissive.minimumPersonAreaRatio, 0.0001f)
        assertTrue(permissive.minimumPersonConfidence < strict.minimumPersonConfidence)
        assertTrue(permissive.minimumPersonAreaRatio < strict.minimumPersonAreaRatio)
    }

    @Test
    fun higherSensitivityAcceptsSmallerFaces() {
        val strict = DetectionSensitivity.fromProgress(0)
        val permissive = DetectionSensitivity.fromProgress(100)

        assertEquals(0.18f, strict.minimumFaceWidthRatio, 0.0001f)
        assertEquals(0.10f, permissive.minimumFaceWidthRatio, 0.0001f)
        assertTrue(permissive.minimumFaceWidthRatio < strict.minimumFaceWidthRatio)
    }
}
