package ai.argvid.gen0

import ai.argvid.gen0.capture.CameraXSampler
import ai.argvid.gen0.capture.DefaultFrameProcessor
import ai.argvid.gen0.capture.ProxyConfiguration
import ai.argvid.gen0.capture.RescueProxyBuffer
import ai.argvid.gen0.domain.capture.CaptureGimbalPort
import ai.argvid.gen0.domain.capture.CapturePreviewPort
import ai.argvid.gen0.domain.capture.CaptureSessionController
import ai.argvid.gen0.domain.gimbal.GimbalController
import ai.argvid.gen0.domain.moment.MomentCoordinator
import ai.argvid.gen0.domain.time.MonotonicClock
import ai.argvid.gen0.gimbal.ManualGimbalScheduler
import ai.argvid.gen0.gimbal.SimulatedGimbalLink
import ai.argvid.gen0.media.codec.AndroidProxyMovieEncoder
import ai.argvid.gen0.media.catalog.RoomMomentCatalog
import ai.argvid.gen0.media.store.ContentResolverMediaStoreClient
import ai.argvid.gen0.media.store.MediaStoreMomentSaver
import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import ai.argvid.gen0.domain.capture.StopReason
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionRuntime(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val mutableWarmupDurationUs = MutableStateFlow(0L)
    val warmupDurationUs: StateFlow<Long> = mutableWarmupDurationUs.asStateFlow()
    private val appContext = context.applicationContext
    private val sessionId = UUID.randomUUID().toString()
    private val sessionStartedAt = Instant.now().toString()
    private val clock = MonotonicClock { System.nanoTime() / 1_000 }
    private val configuration = ProxyConfiguration.p540()
    private val buffer = RescueProxyBuffer(configuration)
    private val sampler = CameraXSampler(
        context = appContext,
        configuration = configuration,
        processor = DefaultFrameProcessor(configuration),
    )
    private val gimbalScheduler = ManualGimbalScheduler()
    val gimbal = GimbalController(
        SimulatedGimbalLink(
            clock = gimbalScheduler,
            scheduler = gimbalScheduler,
            afterMotionScheduled = { gimbalScheduler.advanceBy(300_000) },
        ),
    )
    val capture = CaptureSessionController(
        sampler = sampler,
        buffer = buffer,
        preview = CapturePreviewPort { },
        gimbal = CaptureGimbalPort { scope.launch { gimbal.hold() } },
        clock = clock,
        scope = scope,
        initialState = ai.argvid.gen0.domain.session.SessionState.Idle,
    )
    val moments = MomentCoordinator(
        source = buffer,
        encoder = AndroidProxyMovieEncoder(File(appContext.cacheDir, "rescued-moments")),
        saver = MediaStoreMomentSaver(ContentResolverMediaStoreClient(appContext.contentResolver)),
        catalog = RoomMomentCatalog(
            database = (appContext as Gen0Application).database,
            sessionId = sessionId,
            sessionStartedAt = sessionStartedAt,
        ),
    )

    init {
        scope.launch {
            sampler.frames.collect { frame ->
                if (capture.acceptFrames.value) {
                    buffer.append(frame)
                    val coverage = buffer.coverage(frame.timestampUs, 15_000_000)
                    val actualStartUs = coverage.actualStartUs
                    val actualEndUs = coverage.actualEndUs
                    mutableWarmupDurationUs.value = if (actualStartUs != null && actualEndUs != null) {
                        (actualEndUs - actualStartUs).coerceIn(0, 15_000_000)
                    } else {
                        0
                    }
                    capture.onCoverageUpdated(frame.timestampUs)
                }
            }
        }
    }

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
    ) = capture.bindCamera {
        // Rebind to the current Activity/PreviewView, including after recreation.
        sampler.stop()
        mutableWarmupDurationUs.value = 0
        sampler.start(lifecycleOwner, surfaceProvider, masks = emptyList())
    }

    suspend fun close() {
        moments.onStop()
        capture.stop(StopReason.SessionEnded)
    }
}
