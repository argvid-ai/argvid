package ai.argvid.gen0.domain.detection

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Camera optics needed to turn normalized image error into gimbal angle error. */
data class TrackingOptics(
    val horizontalFovDeg: Double,
    val verticalFovDeg: Double,
) {
    init {
        require(horizontalFovDeg > 0.0)
        require(verticalFovDeg > 0.0)
    }
}

/** One bounded tracking correction in gimbal axes; zero means "hold, do not move". */
data class TrackingCorrection(
    val panDeg: Double,
    val tiltDeg: Double,
) {
    val isZero: Boolean
        get() = panDeg == 0.0 && tiltDeg == 0.0
}

/**
 * Project-local subject tracker: converts subject sightings into small, bounded,
 * rate-limited gimbal corrections that keep the subject near the image center.
 *
 * The tracker owns no I/O and never issues commands itself; the caller translates
 * a returned correction into its gimbal boundary. Corrections are proportional to
 * the angular error, clamped to a per-cycle maximum, suppressed inside a deadzone
 * and below a minimum step (dither guard), and rate-limited so the physical gimbal
 * — whose position loop overshoots — is not excited into oscillation. Axis sign
 * conventions depend on how the camera is mounted on the gimbal and are therefore
 * explicit calibration flags, not assumptions.
 */
class GimbalSubjectTracker(
    private val optics: TrackingOptics,
    private val gain: Double = 0.8,
    private val deadzoneDeg: Double = 3.0,
    private val minCorrectionDeg: Double = 0.5,
    private val maxCorrectionDeg: Double = 6.0,
    private val minCorrectionInterval: Duration = Duration.ofMillis(300),
    private val smoothingAlpha: Double = 0.5,
    private val invertPan: Boolean = false,
    private val invertTilt: Boolean = false,
) {
    init {
        require(gain in 0.0..1.0)
        require(deadzoneDeg >= 0.0)
        require(minCorrectionDeg >= 0.0)
        require(maxCorrectionDeg >= minCorrectionDeg)
        require(smoothingAlpha in 0.0..1.0)
    }

    private var lastCorrectionAt: Instant? = null

    /**
     * Exponential moving average of the per-axis angular error. Vision transport
     * delay makes raw per-frame errors stale and noisy; smoothing before the
     * deadzone suppresses frame jitter that would otherwise dither the gimbal.
     */
    private var smoothedPanErrorDeg = 0.0
    private var smoothedTiltErrorDeg = 0.0

    /**
     * Returns the next correction for [sighting], or null when no correction should
     * be issued now: no subject, no geometry, inside the deadzone, below the minimum
     * step, or sooner than the correction interval after the previous one.
     */
    fun update(sighting: SubjectObservation?, now: Instant): TrackingCorrection? {
        if (sighting == null || !sighting.hasSubject) return null
        val centerX = sighting.centerX ?: return null
        val centerY = sighting.centerY ?: return null

        // Smooth on every geometric sighting so the estimate stays current even
        // while corrections themselves are rate-limited.
        val rawPanErrorDeg = (centerX - CENTER) * optics.horizontalFovDeg * if (invertPan) -1.0 else 1.0
        val rawTiltErrorDeg = (centerY - CENTER) * optics.verticalFovDeg * if (invertTilt) -1.0 else 1.0
        smoothedPanErrorDeg += smoothingAlpha * (rawPanErrorDeg - smoothedPanErrorDeg)
        smoothedTiltErrorDeg += smoothingAlpha * (rawTiltErrorDeg - smoothedTiltErrorDeg)

        lastCorrectionAt?.let { previous ->
            if (Duration.between(previous, now) < minCorrectionInterval) return null
        }

        val pan = boundedStep(smoothedPanErrorDeg)
        val tilt = boundedStep(smoothedTiltErrorDeg)
        if (pan == 0.0 && tilt == 0.0) return null

        lastCorrectionAt = now
        return TrackingCorrection(panDeg = pan, tiltDeg = tilt)
    }

    private fun boundedStep(errorDeg: Double): Double {
        if (abs(errorDeg) < deadzoneDeg) return 0.0
        val step = errorDeg * gain
        val clamped = step.coerceIn(-maxCorrectionDeg, maxCorrectionDeg)
        return if (abs(clamped) < minCorrectionDeg) 0.0 else clamped
    }

    private companion object {
        const val CENTER = 0.5f
    }
}
