package ai.argvid.gen0.media.codec

import ai.argvid.gen0.domain.moment.OwnedPcmAudio
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

internal data class AudioPacket(val bytes: ByteArray, val timeUs: Long, val flags: Int)
internal data class EncodedAudio(val format: MediaFormat, val packets: List<AudioPacket>)

/** Encode the bounded rescue snapshot before starting the two-track muxer. */
internal fun encodeAac(audio: OwnedPcmAudio, job: Job?): EncodedAudio {
    require(audio.sampleRate == 48_000 && audio.pcm16.isNotEmpty() && audio.pcm16.size % 2 == 0)
    require(audio.durationUs <= 20_000_000)
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    var started = false
    try {
        codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        started = true
        var inputOffset = 0
        var inputEnded = false
        var format: MediaFormat? = null
        val packets = mutableListOf<AudioPacket>()
        var totalBytes = 0
        val info = MediaCodec.BufferInfo()
        var lastProgressNs = System.nanoTime()
        while (true) {
            job?.ensureActive()
            check(System.nanoTime() - lastProgressNs < 5_000_000_000) { "Audio encoder stalled" }
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = checkNotNull(codec.getInputBuffer(inputIndex))
                    input.clear()
                    val size = minOf(input.remaining(), audio.pcm16.size - inputOffset, 4096) / 2 * 2
                    val timeUs = inputOffset / 2L * 1_000_000 / audio.sampleRate
                    if (size == 0) {
                        check(inputOffset == audio.pcm16.size)
                        codec.queueInputBuffer(inputIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        input.put(audio.pcm16, inputOffset, size)
                        codec.queueInputBuffer(inputIndex, 0, size, timeUs, 0)
                        inputOffset += size
                    }
                    lastProgressNs = System.nanoTime()
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.outputFormat
            if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        totalBytes += bytes.size
                        check(totalBytes <= 1_000_000) { "Encoded audio exceeded bounded snapshot budget" }
                        packets += AudioPacket(bytes, info.presentationTimeUs.coerceAtLeast(0), info.flags)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } finally { codec.releaseOutputBuffer(index, false) }
                lastProgressNs = System.nanoTime()
            }
        }
        check(packets.isNotEmpty()) { "Audio encoder produced no samples" }
        return EncodedAudio(checkNotNull(format), packets)
    } finally {
        if (started) runCatching { codec.stop() }
        codec.release()
    }
}
