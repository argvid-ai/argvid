package ai.argvid.gen0.domain.detection

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GimbalSubjectTrackerTest {
    private val optics = TrackingOptics(horizontalFovDeg = 60.0, verticalFovDeg = 40.0)
    private val start = Instant.parse("2026-09-14T10:00:00Z")

    @Test
    fun centeredSightingProducesNoCorrection() {
        val tracker = GimbalSubjectTracker(optics)
        assertNull(tracker.update(sighting(centerX = 0.5f, centerY = 0.5f), start))
    }

    @Test
    fun missingSubjectOrGeometryProducesNoCorrection() {
        val tracker = GimbalSubjectTracker(optics)
        assertNull(tracker.update(null, start))
        assertNull(tracker.update(SubjectObservation(emptySet(), start), start))
        assertNull(tracker.update(sighting(centerX = null, centerY = null), start))
    }

    @Test
    fun offCenterSightingProducesProportionalSignedCorrection() {
        val tracker = GimbalSubjectTracker(optics)
        // Subject at the right image edge and below center: pan error +30°, tilt
        // error +20° with the default convention; both clamp to the 6° maximum.
        val correction = tracker.update(sighting(centerX = 1.0f, centerY = 1.0f), start)
        assertNotNull(correction)
        assertEquals(6.0, correction!!.panDeg, 1e-9)
        assertEquals(6.0, correction.tiltDeg, 1e-9)
    }

    @Test
    fun smallErrorInsideDeadzoneIsSuppressed() {
        val tracker = GimbalSubjectTracker(optics, deadzoneDeg = 0.5, smoothingAlpha = 1.0)
        // Pan error (0.5+1/60-0.5)*60° = 1° → above deadzone → 1° step; tilt error
        // (0.51-0.5)*40° = 0.4° → inside the deadzone → suppressed.
        val correction = tracker.update(sighting(centerX = 0.5166667f, centerY = 0.51f), start)
        assertNotNull(correction)
        assertEquals(0.8, correction!!.panDeg, 1e-4)
        assertEquals(0.0, correction.tiltDeg, 1e-9)
    }

    @Test
    fun correctionsAreRateLimited() {
        val tracker = GimbalSubjectTracker(optics, minCorrectionInterval = Duration.ofMillis(400))
        assertNotNull(tracker.update(sighting(centerX = 1.0f, centerY = 0.5f), start))
        assertNull("second sighting within the interval must not correct", tracker.update(sighting(centerX = 1.0f, centerY = 0.5f), start.plusMillis(200)))
        assertNotNull(
            "sighting after the interval corrects again",
            tracker.update(sighting(centerX = 1.0f, centerY = 0.5f), start.plusMillis(401)),
        )
    }

    @Test
    fun stepBelowMinimumIsSuppressedToAvoidDither() {
        val tracker = GimbalSubjectTracker(optics, minCorrectionDeg = 0.8, deadzoneDeg = 2.0, smoothingAlpha = 1.0)
        // Pan error 3° → step 3°*0.8 OK; tilt error 0.7° → step 0.56° < 0.8 → suppressed.
        val correction = tracker.update(sighting(centerX = 0.55f, centerY = 0.5175f), start)
        assertNotNull(correction)
        assertEquals(2.4, correction!!.panDeg, 1e-4)
        assertEquals(0.0, correction.tiltDeg, 1e-9)
    }

    @Test
    fun invertedAxisFlipsTheCorrectionSign() {
        val tracker = GimbalSubjectTracker(optics, invertPan = true, invertTilt = true)
        val correction = tracker.update(sighting(centerX = 1.0f, centerY = 1.0f), start)
        assertNotNull(correction)
        assertEquals(-6.0, correction!!.panDeg, 1e-9)
        assertEquals(-6.0, correction.tiltDeg, 1e-9)
    }

    @Test
    fun gainScalesTheCorrection() {
        val tracker = GimbalSubjectTracker(optics, gain = 0.5, deadzoneDeg = 1.0, smoothingAlpha = 1.0)
        // Pan error 6° → step 3° (clamped); tilt error 2° → step 1° with gain 0.5.
        val correction = tracker.update(sighting(centerX = 0.6f, centerY = 0.55f), start)
        assertNotNull(correction)
        assertEquals(3.0, correction!!.panDeg, 1e-4)
        assertEquals(1.0, correction.tiltDeg, 1e-4)
    }

    @Test
    fun alternatingFrameNoiseNeverReachesTheDeadzone() {
        val tracker = GimbalSubjectTracker(optics)
        // Raw errors alternate +4°/-4° (frame jitter); the EMA keeps the smoothed
        // error under the 3° deadzone on every frame, so nothing is ever commanded.
        var at = start
        repeat(6) { index ->
            val offset = if (index % 2 == 0) 4.0 else -4.0
            val correction = tracker.update(sighting(centerX = (0.5 + offset / 60.0).toFloat(), centerY = 0.5f), at)
            assertNull("iteration $index must not correct", correction)
            at = at.plusMillis(350)
        }
    }

    @Test
    fun sustainedErrorConvergesThroughTheSmoother() {
        val tracker = GimbalSubjectTracker(optics)
        // Constant 6° pan error: first frame smooths to 3° (step 2.4), the next
        // converges to 4.5° (step 3.6) — corrections grow toward the true error.
        val first = tracker.update(sighting(centerX = 0.6f, centerY = 0.5f), start)
        assertEquals(2.4, first!!.panDeg, 1e-6)
        val second = tracker.update(sighting(centerX = 0.6f, centerY = 0.5f), start.plusMillis(350))
        assertEquals(3.6, second!!.panDeg, 1e-6)
    }

    private fun sighting(centerX: Float?, centerY: Float?): SubjectObservation =
        SubjectObservation(
            labels = setOf(SubjectLabel.PERSON),
            observedAt = start,
            centerX = centerX,
            centerY = centerY,
        )
}
