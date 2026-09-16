package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.detection.SubjectObservation
import ai.argvid.gen0.domain.time.WallClock
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Face detection pipeline over the reviewed MediaPipe BlazeFace short-range
 * model: taps the proxy JPEG frame stream, decodes at reduced resolution,
 * throttles to a fixed analysis cadence, runs on-device inference on a single
 * serialized worker, and emits domain [SubjectObservation]s — including empty
 * (absent) sightings so downstream presence rules see absence transitions.
 * The model loads lazily on first start; frame pixels are never stored or
 * forwarded. Frames are assumed upright as encoded (same orientation as the
 * rescue encoder); gimbal-axis sign calibration lives in the tracker, not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceDetectionPipeline(
    context: Context,
    private val frames: Flow<ProxyFrame>,
    private val scope: CoroutineScope,
    private val clock: WallClock,
    inferenceDispatcher: CoroutineDispatcher,
    private val analysisIntervalMs: Long = DEFAULT_ANALYSIS_INTERVAL_MS,
) {
    private val appContext by lazy { context.applicationContext }
    private val inferenceWorker = inferenceDispatcher.limitedParallelism(1)
    private var detector: FaceDetector? = null
    private var collectJob: Job? = null

    @Volatile private var minimumConfidence = DEFAULT_MIN_CONFIDENCE
    @Volatile private var minimumWidthRatio = DEFAULT_MIN_WIDTH_RATIO
    private var consecutiveAbsences = 0
    private var lastPresentObservation: SubjectObservation? = null

    /** Last raw face center (normalized) anchoring the zoom crop; null = frame center. */
    @Volatile private var lastFaceCenterX: Float? = null
    @Volatile private var lastFaceCenterY: Float? = null

    private val observationsBus = MutableSharedFlow<SubjectObservation>(extraBufferCapacity = 8)
    private val failureBus = MutableStateFlow<String?>(null)

    /** Latest sighting per analysis cycle; absent sightings carry empty labels. */
    val observations: SharedFlow<SubjectObservation> = observationsBus.asSharedFlow()

    /** Non-null after an unrecoverable detector error; restart requires a new pipeline. */
    val failure: StateFlow<String?> = failureBus.asStateFlow()

    fun updateThresholds(minConfidence: Float, minWidthRatio: Float) {
        minimumConfidence = minConfidence
        minimumWidthRatio = minWidthRatio
    }

    fun start() {
        if (collectJob?.isActive == true) return
        failureBus.value = null
        collectJob = scope.launch {
            var latest: ProxyFrame? = null
            val latestFrame = launch {
                frames.collect { frame ->
                    val current = latest
                    if (current == null || frame.timestampUs >= current.timestampUs) latest = frame
                }
            }
            try {
                while (isActive) {
                    delay(analysisIntervalMs)
                    val frame = latest ?: continue
                    latest = null
                    val observation = runCatching { analyze(frame) }
                        .getOrElse { error ->
                            fail(error.message ?: "face detection failed")
                            continue
                        }
                    observationsBus.tryEmit(observation)
                }
            } finally {
                latestFrame.cancel()
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    fun close() {
        stop()
        detector?.close()
        detector = null
    }

    private fun analyze(frame: ProxyFrame): SubjectObservation {
        val now = clock.now()
        val bitmap = decode(frame) ?: run {
            if (failureBus.value == null) android.util.Log.w(TAG, "frame decode returned null (${frame.jpeg.size} bytes)")
            return emit(FaceDetections.toObservation(best = null, observedAt = now))
        }
        val raw = detectLargestFace(bitmap)
        val candidate = raw?.takeIf { it.score >= minimumConfidence && it.widthRatio >= minimumWidthRatio }
        if (raw != null && candidate == null) {
            android.util.Log.i(
                TAG,
                "face gated: width=${"%.3f".format(raw.widthRatio)} < ${"%.3f".format(minimumWidthRatio)} score=${"%.2f".format(raw.score)}",
            )
        }
        if (raw != null) {
            lastFaceCenterX = (raw.left + raw.right) / 2f
            lastFaceCenterY = (raw.top + raw.bottom) / 2f
        }
        return emit(FaceDetections.toObservation(candidate, now))
    }

    /**
     * Presence hysteresis: a face reports present immediately, but absence is only
     * emitted after a few consecutive missed cycles — single blurred or missed
     * frames (e.g. while the phone moves) no longer flip the status to absent.
     * Held-over sightings keep ONLY the presence label: re-stamping stale geometry
     * as fresh would drive the tracker toward where the face used to be.
     */
    private fun emit(observation: SubjectObservation): SubjectObservation {
        if (observation.hasSubject) {
            consecutiveAbsences = 0
            lastPresentObservation = observation
            android.util.Log.i(TAG, "face center=(${observation.centerX}, ${observation.centerY})")
            return observation
        }
        consecutiveAbsences += 1
        if (consecutiveAbsences >= ABSENCE_CONFIRM_CYCLES) {
            lastFaceCenterX = null
            lastFaceCenterY = null
        }
        return if (consecutiveAbsences >= ABSENCE_CONFIRM_CYCLES || lastPresentObservation == null) {
            lastPresentObservation = null
            observation
        } else {
            SubjectObservation(
                labels = lastPresentObservation!!.labels,
                observedAt = observation.observedAt,
            )
        }
    }

    private fun decode(frame: ProxyFrame): Bitmap? {
        // Half resolution keeps ratios and centers identical while cutting decode
        // cost; BlazeFace runs on a far smaller input anyway.
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        return BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, options)
    }

    /**
     * Largest face by area among confidence-passing candidates. Confidence must be
     * filtered BEFORE selecting: this old FaceDetector API returns a placeholder
     * detection (full-frame box, score 0.0) when it finds nothing, which would
     * otherwise always win the largest-area pick and starve real faces.
     *
     * When the full frame yields nothing, a second pass runs on a crop **centered
     * on the last known face position** (falling back to the frame center) — the
     * model's small input makes distant faces invisible at full scale, and a
     * fixed central crop would leave the frame edges as a blind ring.
     */
    private fun detectLargestFace(bitmap: Bitmap): FaceCandidate? {
        detectOn(bitmap, 0, 0, bitmap.width, bitmap.height)?.let { return it }
        val cropWidth = (bitmap.width * ROI_FRACTION).toInt()
        val cropHeight = (bitmap.height * ROI_FRACTION).toInt()
        val centerX = ((lastFaceCenterX ?: 0.5f) * bitmap.width).toInt()
        val centerY = ((lastFaceCenterY ?: 0.5f) * bitmap.height).toInt()
        val offsetX = (centerX - cropWidth / 2).coerceIn(0, bitmap.width - cropWidth)
        val offsetY = (centerY - cropHeight / 2).coerceIn(0, bitmap.height - cropHeight)
        val crop = Bitmap.createBitmap(bitmap, offsetX, offsetY, cropWidth, cropHeight)
        return detectOn(crop, offsetX, offsetY, bitmap.width, bitmap.height)
    }

    /** [fullWidth]/[fullHeight] normalize coordinates back to the whole frame. */
    private fun detectOn(
        source: Bitmap,
        offsetX: Int,
        offsetY: Int,
        fullWidth: Int,
        fullHeight: Int,
    ): FaceCandidate? {
        val activeDetector = detector ?: createDetector() ?: return null
        val image = BitmapImageBuilder(source).build()
        val detections = activeDetector.detect(image).detections()
        val width = fullWidth.toFloat()
        val height = fullHeight.toFloat()
        return detections.map { detection ->
            val box = detection.boundingBox()
            val score = detection.categories().firstOrNull()?.score() ?: 0f
            FaceCandidate(
                left = (offsetX + box.left) / width,
                top = (offsetY + box.top) / height,
                right = (offsetX + box.right) / width,
                bottom = (offsetY + box.bottom) / height,
                score = score,
            )
        }
            .filter { it.score >= minimumConfidence }
            .maxByOrNull { it.areaRatio }
            // A crop-pass box leaking outside its crop would be a mapping bug.
            ?.takeIf { offsetX == 0 || it.widthRatio < 1f }
    }

    private fun createDetector(): FaceDetector? {
        val created = runCatching {
            FaceDetector.createFromOptions(
                appContext,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setDelegate(Delegate.CPU)
                            .setModelAssetPath(MODEL_ASSET)
                            .build(),
                    )
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(MODEL_SCORE_THRESHOLD)
                    .build(),
            )
        }.onFailure { error ->
            fail(error.message ?: "face detector failed to initialize")
        }.getOrNull()
        if (created != null) android.util.Log.i(TAG, "face detector loaded: $MODEL_ASSET")
        detector = created
        return created
    }

    private fun fail(message: String) {
        if (failureBus.value == null) {
            failureBus.value = message
            android.util.Log.e(TAG, "face detection failed: $message")
        }
    }

    private companion object {
        const val TAG = "FACE_DETECT"
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
        const val ABSENCE_CONFIRM_CYCLES = 3

        /** Central-crop second pass: zoom for distant faces the 128px model input misses. */
        const val ROI_FRACTION = 0.6f

        /** The model's own threshold stays permissive; sensitivity gates locally. */
        const val MODEL_SCORE_THRESHOLD = 0f
        const val DEFAULT_MIN_CONFIDENCE = 0.5f
        const val DEFAULT_MIN_WIDTH_RATIO = 0.10f
        const val DEFAULT_ANALYSIS_INTERVAL_MS = 120L
    }
}
