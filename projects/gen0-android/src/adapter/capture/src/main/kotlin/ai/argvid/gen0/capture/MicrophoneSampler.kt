package ai.argvid.gen0.capture

import android.Manifest
import androidx.annotation.RequiresPermission
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import ai.argvid.gen0.domain.capture.CaptureSamplerPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground-only PCM16 microphone owner; nonblocking reads allow finite stop/release. */
class MicrophoneSampler(private val context: Context, private val scope: CoroutineScope) : CaptureSamplerPort {
    private val mutex = Mutex()
    private var reader: Job? = null
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    override val isRunning: Boolean get() = reader?.isActive == true && mutableFailure.value == null

    @OptIn(DelicateCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun start(onSamples: (Long, ByteArray) -> Unit) = mutex.withLock {
        reader?.cancelAndJoin()
        mutableFailure.value = null
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "麦克风权限未授予"
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持所需的麦克风采样格式" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, SAMPLE_RATE / 5 * 2))
            .build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败" }
        } catch (error: Exception) {
            recorder.release()
            throw error
        }
        // Enter the release finally even if the owner is cancelled before IO dispatch.
        reader = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val bytes = ByteArray(960 * 2)
            val stamp = AudioTimestamp()
            var readFrames = 0L
            var lastDataNs = System.nanoTime()
            var lastTimestampNs = lastDataNs
            try {
                while (currentCoroutineContext().isActive) {
                    check(recorder.activeRecordingConfiguration?.isClientSilenced != true) {
                        "麦克风被系统静音或被其他应用占用"
                    }
                    val count = recorder.read(bytes, 0, bytes.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0 && count % 2 == 0) { "麦克风读取失败" }
                    val nowNs = System.nanoTime()
                    if (count == 0) {
                        check(nowNs - lastDataNs < 2_000_000_000) { "麦克风未返回声音数据" }
                        delay(5)
                        continue
                    }
                    lastDataNs = nowNs
                    val firstFrame = readFrames
                    readFrames += count / 2
                    if (recorder.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val startUs = stamp.nanoTime / 1_000 + (firstFrame - stamp.framePosition) * 1_000_000 / SAMPLE_RATE
                        if (startUs >= 0) onSamples(startUs, bytes.copyOf(count))
                        lastTimestampNs = nowNs
                    } else {
                        check(nowNs - lastTimestampNs < 2_000_000_000) { "无法同步麦克风时间，请重新启动预检" }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                mutableFailure.value = "麦克风权限已失效，采集已停止"
            } catch (error: Exception) {
                mutableFailure.value = error.message ?: "麦克风采集失败"
            } finally {
                bytes.fill(0)
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    override suspend fun stop() = mutex.withLock {
        reader?.cancelAndJoin()
        reader = null
    }

    companion object { const val SAMPLE_RATE = 48_000 }
}
