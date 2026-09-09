package ai.argvid.gen0.domain.detection

import java.time.Duration
import java.time.Instant
import kotlin.ConsistentCopyVisibility

enum class SubjectLabel {
    PERSON,
    FACE,
}

data class SubjectObservation(
    val labels: Set<SubjectLabel>,
    val observedAt: Instant,
) {
    val hasSubject: Boolean
        get() = labels.isNotEmpty()
}

enum class AutomaticRecordingDecision {
    Start,
    Stop,
}

/** Project-local timing policy. It produces suggestions and owns no capture side effects. */
class AutomaticRecordingPolicy(
    private val appearanceThreshold: Duration = Duration.ofSeconds(2),
    private val absenceThreshold: Duration = Duration.ofSeconds(3),
    private val cooldown: Duration = Duration.ofSeconds(15),
) {
    private var presenceStartedAt: Instant? = null
    private var absenceStartedAt: Instant? = null
    private var recordingSuggested = false
    private var cooldownUntil: Instant? = null

    fun update(subjectPresent: Boolean, now: Instant): AutomaticRecordingDecision? {
        if (cooldownUntil?.let(now::isBefore) == true) {
            presenceStartedAt = null
            absenceStartedAt = null
            return null
        }
        cooldownUntil = null

        if (recordingSuggested) {
            if (subjectPresent) {
                absenceStartedAt = null
                return null
            }

            val startedAt = absenceStartedAt ?: now.also { absenceStartedAt = it }
            if (Duration.between(startedAt, now) >= absenceThreshold) {
                recordingSuggested = false
                absenceStartedAt = null
                presenceStartedAt = null
                cooldownUntil = now.plus(cooldown)
                return AutomaticRecordingDecision.Stop
            }
            return null
        }

        if (!subjectPresent) {
            presenceStartedAt = null
            return null
        }

        val startedAt = presenceStartedAt ?: now.also { presenceStartedAt = it }
        if (Duration.between(startedAt, now) >= appearanceThreshold) {
            recordingSuggested = true
            presenceStartedAt = null
            return AutomaticRecordingDecision.Start
        }
        return null
    }

    fun reset() {
        presenceStartedAt = null
        absenceStartedAt = null
        recordingSuggested = false
        cooldownUntil = null
    }
}

@ConsistentCopyVisibility
data class DetectionSensitivity private constructor(
    val progress: Int,
) {
    private val normalizedProgress: Float
        get() = progress.toFloat() / MAX_PROGRESS

    val minimumPersonConfidence: Float
        get() = MAXIMUM_PERSON_CONFIDENCE -
            (MAXIMUM_PERSON_CONFIDENCE - MINIMUM_PERSON_CONFIDENCE) * normalizedProgress

    val minimumPersonAreaRatio: Float
        get() = MAXIMUM_PERSON_AREA_RATIO -
            (MAXIMUM_PERSON_AREA_RATIO - MINIMUM_PERSON_AREA_RATIO) * normalizedProgress

    val minimumFaceWidthRatio: Float
        get() = MAXIMUM_FACE_WIDTH_RATIO -
            (MAXIMUM_FACE_WIDTH_RATIO - MINIMUM_FACE_WIDTH_RATIO) * normalizedProgress

    companion object {
        const val MAX_PROGRESS = 100
        const val DEFAULT_PROGRESS = 50

        val DEFAULT = fromProgress(DEFAULT_PROGRESS)

        fun fromProgress(progress: Int): DetectionSensitivity =
            DetectionSensitivity(progress.coerceIn(0, MAX_PROGRESS))

        private const val MAXIMUM_PERSON_CONFIDENCE = 0.80f
        private const val MINIMUM_PERSON_CONFIDENCE = 0.50f
        private const val MAXIMUM_PERSON_AREA_RATIO = 0.08f
        private const val MINIMUM_PERSON_AREA_RATIO = 0.01f
        private const val MAXIMUM_FACE_WIDTH_RATIO = 0.18f
        private const val MINIMUM_FACE_WIDTH_RATIO = 0.10f
    }
}
