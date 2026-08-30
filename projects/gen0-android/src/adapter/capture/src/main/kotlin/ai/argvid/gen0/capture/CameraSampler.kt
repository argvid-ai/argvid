package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.capture.CaptureSamplerPort
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class CameraCapability(
    val proxyWidth: Int,
    val proxyHeight: Int,
    val targetFps: Int,
    val previewAndAnalysis: Boolean,
)

sealed interface CameraSamplerState {
    data object Stopped : CameraSamplerState
    data object Starting : CameraSamplerState
    data object Running : CameraSamplerState
    data class Failed(val reason: CameraSamplerFailure) : CameraSamplerState
}

enum class CameraSamplerFailure {
    AlreadyRunning,
    PermissionDenied,
    PreviewAnalysisBindFailed,
}

class CameraSamplerException(
    val reason: CameraSamplerFailure,
    cause: Throwable? = null,
) : IllegalStateException(reason.name, cause)

sealed interface CameraSamplerEvent {
    data class FrameRejected(val reason: ai.argvid.gen0.capture.FrameRejected) : CameraSamplerEvent
    data class Failed(val reason: CameraSamplerFailure) : CameraSamplerEvent
}

data class CameraSamplerDiagnostics(
    val acceptedFrames: Long = 0,
    val throttledFrames: Long = 0,
    val rejectedFrames: Long = 0,
)

interface CameraSampler : CaptureSamplerPort {
    val frames: Flow<ProxyFrame>
    val events: Flow<CameraSamplerEvent>
    val state: StateFlow<CameraSamplerState>
    val diagnostics: StateFlow<CameraSamplerDiagnostics>
    val capability: CameraCapability
    override val isRunning: Boolean
        get() = state.value == CameraSamplerState.Running

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        masks: List<FrameMask>,
    )

    override suspend fun stop()
}

interface CloseableCameraFrame {
    val timestampUs: Long
    val geometry: CameraGeometry
    fun readYuv(): YuvFrame
    fun close()
}

internal class MaskMappingException : IllegalArgumentException()

class CameraFrameAnalyzer(
    private val targetIntervalUs: Long,
    private val processor: FrameProcessor,
    masks: List<FrameMask>,
    private val onAccepted: (ProxyFrame) -> Unit,
    private val onRejected: (FrameRejected) -> Unit,
    private val onThrottled: () -> Unit = {},
) {
    private val masks = masks.toList()
    private var lastProcessedTimestampUs: Long? = null

    init {
        require(targetIntervalUs > 0)
    }

    fun analyze(frame: CloseableCameraFrame) {
        try {
            val previous = lastProcessedTimestampUs
            if (previous != null) {
                if (frame.timestampUs <= previous) {
                    onRejected(FrameRejected.InvalidSource)
                    return
                }
                if (frame.timestampUs - previous < targetIntervalUs) {
                    onThrottled()
                    return
                }
            }
            lastProcessedTimestampUs = frame.timestampUs
            when (val result = processor.process(frame.readYuv(), frame.geometry, masks, frame.timestampUs)) {
                is FrameProcessResult.Accepted -> onAccepted(result.frame)
                is FrameProcessResult.Rejected -> onRejected(result.reason)
            }
        } catch (_: MaskMappingException) {
            onRejected(FrameRejected.MaskMappingFailed)
        } catch (_: RuntimeException) {
            onRejected(FrameRejected.EncodingFailed)
        } finally {
            frame.close()
        }
    }
}
