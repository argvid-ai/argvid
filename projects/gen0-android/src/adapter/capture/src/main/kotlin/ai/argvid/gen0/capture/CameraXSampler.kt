package ai.argvid.gen0.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class CameraXSampler(
    context: Context,
    private val configuration: ProxyConfiguration,
    private val processor: FrameProcessor,
) : CameraSampler {
    private val appContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow<CameraSamplerState>(CameraSamplerState.Stopped)
    private val mutableDiagnostics = MutableStateFlow(CameraSamplerDiagnostics())
    private val frameBus = MutableSharedFlow<ProxyFrame>(extraBufferCapacity = 4)
    private val eventBus = MutableSharedFlow<CameraSamplerEvent>(extraBufferCapacity = 16)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var analyzerExecutor: ExecutorService? = null

    override val frames: Flow<ProxyFrame> = frameBus.asSharedFlow()
    override val events: Flow<CameraSamplerEvent> = eventBus.asSharedFlow()
    override val state: StateFlow<CameraSamplerState> = mutableState.asStateFlow()
    override val diagnostics: StateFlow<CameraSamplerDiagnostics> = mutableDiagnostics.asStateFlow()
    override val capability = CameraCapability(
        proxyWidth = configuration.width,
        proxyHeight = configuration.height,
        targetFps = configuration.targetFps,
        previewAndAnalysis = true,
    )

    override suspend fun start(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        masks: List<FrameMask>,
    ) = lifecycleMutex.withLock {
        if (mutableState.value != CameraSamplerState.Stopped) {
            throw CameraSamplerException(CameraSamplerFailure.AlreadyRunning)
        }
        mutableState.value = CameraSamplerState.Starting
        try {
            val provider = awaitCameraProvider()
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "gen0-camera-analysis")
            }
            val frameAnalyzer = CameraFrameAnalyzer(
                targetIntervalUs = configuration.targetIntervalUs,
                processor = processor,
                masks = masks,
                onAccepted = { frame ->
                    if (frameBus.tryEmit(frame)) {
                        mutableDiagnostics.update { it.copy(acceptedFrames = it.acceptedFrames + 1) }
                    } else {
                        mutableDiagnostics.update { it.copy(throttledFrames = it.throttledFrames + 1) }
                    }
                },
                onRejected = { reason ->
                    mutableDiagnostics.update { it.copy(rejectedFrames = it.rejectedFrames + 1) }
                    eventBus.tryEmit(CameraSamplerEvent.FrameRejected(reason))
                },
                onThrottled = {
                    mutableDiagnostics.update { it.copy(throttledFrames = it.throttledFrames + 1) }
                },
            )
            cameraProvider = provider
            analyzerExecutor = executor
            withContext(Dispatchers.Main.immediate) {
                val nextPreview = Preview.Builder().build()
                preview = nextPreview
                nextPreview.setSurfaceProvider(surfaceProvider)
                val nextAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                    .build()
                analysis = nextAnalysis
                nextAnalysis.setAnalyzer(executor) { image ->
                    frameAnalyzer.analyze(ImageProxyCameraFrame(image))
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    nextPreview,
                    nextAnalysis,
                )
            }
            mutableState.value = CameraSamplerState.Running
        } catch (cancellation: CancellationException) {
            cleanupBindings()
            mutableState.value = CameraSamplerState.Stopped
            throw cancellation
        } catch (security: SecurityException) {
            failStart(CameraSamplerFailure.PermissionDenied, security)
        } catch (error: Exception) {
            failStart(CameraSamplerFailure.PreviewAnalysisBindFailed, error)
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        cleanupBindings()
        mutableState.value = CameraSamplerState.Stopped
    }

    private suspend fun failStart(reason: CameraSamplerFailure, cause: Throwable): Nothing {
        cleanupBindings()
        mutableState.value = CameraSamplerState.Failed(reason)
        eventBus.tryEmit(CameraSamplerEvent.Failed(reason))
        throw CameraSamplerException(reason, cause)
    }

    private suspend fun cleanupBindings() {
        val provider = cameraProvider
        val boundPreview = preview
        val boundAnalysis = analysis
        withContext(Dispatchers.Main.immediate) {
            boundAnalysis?.clearAnalyzer()
            if (provider != null && boundPreview != null && boundAnalysis != null) {
                provider.unbind(boundPreview, boundAnalysis)
            }
        }
        analyzerExecutor?.shutdownNow()
        cameraProvider = null
        preview = null
        analysis = null
        analyzerExecutor = null
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}

private class ImageProxyCameraFrame(
    private val image: ImageProxy,
) : CloseableCameraFrame {
    override val timestampUs: Long = image.imageInfo.timestamp / 1_000

    override val geometry: CameraGeometry
        get() {
            val crop = image.cropRect
            if (image.width <= 0 || image.height <= 0 || crop.width() <= 0 || crop.height() <= 0) {
                throw MaskMappingException()
            }
            return try {
                val rotationDegrees = image.imageInfo.rotationDegrees
                val sourceCrop = NormalizedCrop(
                    left = crop.left.toFloat() / image.width,
                    top = crop.top.toFloat() / image.height,
                    right = crop.right.toFloat() / image.width,
                    bottom = crop.bottom.toFloat() / image.height,
                )
                CameraGeometry(
                    rotationDegrees = rotationDegrees,
                    crop = mapCropAfterRotation(sourceCrop, rotationDegrees),
                )
            } catch (_: IllegalArgumentException) {
                throw MaskMappingException()
            }
        }

    override fun readYuv(): YuvFrame {
        if (image.width % 2 != 0 || image.height % 2 != 0 || image.planes.size != 3) {
            throw IllegalArgumentException("unsupported YUV layout")
        }
        val width = image.width
        val height = image.height
        val ySize = width * height
        val output = ByteArray(ySize + ySize / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        copyPlane(yPlane, width, height, output, 0)
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                val outputIndex = ySize + row * width + column * 2
                output[outputIndex] = sample(vPlane, row, column)
                output[outputIndex + 1] = sample(uPlane, row, column)
            }
        }
        return YuvFrame.Nv21(width, height, output)
    }

    override fun close() {
        image.close()
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ) {
        for (row in 0 until height) {
            for (column in 0 until width) {
                destination[destinationOffset + row * width + column] = sample(plane, row, column)
            }
        }
    }

    private fun sample(plane: ImageProxy.PlaneProxy, row: Int, column: Int): Byte {
        val buffer = plane.buffer
        val index = buffer.position() + row * plane.rowStride + column * plane.pixelStride
        if (index !in buffer.position() until buffer.limit()) throw IllegalArgumentException("plane is truncated")
        return buffer.get(index)
    }
}
