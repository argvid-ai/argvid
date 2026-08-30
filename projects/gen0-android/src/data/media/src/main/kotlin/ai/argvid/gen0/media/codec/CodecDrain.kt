package ai.argvid.gen0.media.codec

import android.media.MediaCodec
import android.media.MediaMuxer

internal class CodecDrain(
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    var muxerStarted: Boolean = false
        private set
    private var trackIndex = -1

    fun drain(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    emptyPolls += 1
                    check(emptyPolls <= MAX_END_OF_STREAM_POLLS) { "Encoder did not produce end of stream" }
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder output format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                else -> if (outputIndex >= 0) {
                    emptyPolls = 0
                    val encoded = checkNotNull(codec.getOutputBuffer(outputIndex))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxerStarted) { "Encoded data arrived before output format" }
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                    }
                    val reachedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (reachedEnd) return
                }
            }
        }
    }

    private companion object {
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val MAX_END_OF_STREAM_POLLS = 500
    }
}
