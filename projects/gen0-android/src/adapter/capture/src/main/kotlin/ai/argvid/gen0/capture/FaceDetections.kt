package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.detection.SubjectLabel
import ai.argvid.gen0.domain.detection.SubjectObservation
import java.time.Instant

/** One normalized face candidate from the detector, all values in [0,1]. */
data class FaceCandidate(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
) {
    val widthRatio: Float
        get() = right - left

    val areaRatio: Float
        get() = (right - left) * (bottom - top)

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f
}

/**
 * Pure mapping from detector candidates to the domain sighting. Only candidates
 * meeting the caller's sensitivity thresholds (confidence and face-width ratio)
 * are considered; the largest one becomes the sighting geometry. No candidate
 * yields an empty (absent) sighting, which is what the recording policy and
 * tracker expect between detections.
 */
object FaceDetections {
    fun bestFace(
        candidates: List<FaceCandidate>,
        minConfidence: Float,
        minWidthRatio: Float,
    ): FaceCandidate? = candidates
        .filter { it.score >= minConfidence && it.widthRatio >= minWidthRatio }
        .maxByOrNull { it.areaRatio }

    fun toObservation(best: FaceCandidate?, observedAt: Instant): SubjectObservation =
        if (best == null) {
            SubjectObservation(labels = emptySet(), observedAt = observedAt)
        } else {
            SubjectObservation(
                labels = setOf(SubjectLabel.FACE),
                observedAt = observedAt,
                centerX = best.centerX,
                centerY = best.centerY,
            )
        }
}
