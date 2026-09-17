package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.detection.SubjectLabel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceDetectionsTest {
    private val now = Instant.parse("2026-09-14T12:00:00Z")

    @Test
    fun bestFacePrefersTheLargestQualifyingCandidate() {
        val best = FaceDetections.bestFace(
            listOf(
                candidate(left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.2f, score = 0.9f),
                candidate(left = 0.3f, top = 0.3f, right = 0.7f, bottom = 0.7f, score = 0.7f),
            ),
            minConfidence = 0.5f,
            minWidthRatio = 0.05f,
        )
        assertEquals(0.3f, best!!.left, 1e-9f)
    }

    @Test
    fun candidatesBelowConfidenceOrWidthAreFiltered() {
        assertNull(
            FaceDetections.bestFace(
                listOf(candidate(score = 0.4f), candidate(left = 0.46f, right = 0.5f)),
                minConfidence = 0.5f,
                minWidthRatio = 0.05f,
            ),
        )
    }

    @Test
    fun emptyInputYieldsNoCandidate() {
        assertNull(FaceDetections.bestFace(emptyList(), 0f, 0f))
    }

    @Test
    fun observationCarriesCenterGeometryForFace() {
        val observation = FaceDetections.toObservation(
            candidate(left = 0.2f, top = 0.3f, right = 0.6f, bottom = 0.7f, score = 0.8f),
            now,
        )
        assertEquals(setOf(SubjectLabel.FACE), observation.labels)
        assertEquals(0.4f, observation.centerX!!, 1e-6f)
        assertEquals(0.5f, observation.centerY!!, 1e-6f)
        assertEquals(now, observation.observedAt)
    }

    @Test
    fun absentObservationHasNoLabelsAndNoGeometry() {
        val observation = FaceDetections.toObservation(best = null, observedAt = now)
        assertEquals(emptySet<SubjectLabel>(), observation.labels)
        assertNull(observation.centerX)
        assertNull(observation.centerY)
    }

    private fun candidate(
        left: Float = 0.0f,
        top: Float = 0.0f,
        right: Float = 1.0f,
        bottom: Float = 1.0f,
        score: Float = 0.9f,
    ) = FaceCandidate(left, top, right, bottom, score)
}
