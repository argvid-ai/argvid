package ai.argvid.gen0.media.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import ai.argvid.gen0.domain.moment.EncodedMoment
import ai.argvid.gen0.domain.moment.MomentEncoder
import ai.argvid.gen0.domain.moment.OwnedRescueAsset
import ai.argvid.gen0.domain.moment.RescueFrame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidProxyMovieEncoder(
    private val stagingDirectory: File,
) : MomentEncoder {
    override suspend fun encode(asset: OwnedRescueAsset): EncodedMoment = withContext(Dispatchers.Default) {
        validate(asset)
        check(stagingDirectory.exists() || stagingDirectory.mkdirs())
        val output = File.createTempFile("gen0-rescue-", ".mp4", stagingDirectory)
        encodeToFile(asset, output)
    }

    override suspend fun discard(moment: EncodedMoment) = withContext(Dispatchers.IO) {
        val file = File(moment.stagingPath)
        check(!file.exists() || file.delete()) { "Unable to delete staged moment" }
    }

    private fun encodeToFile(asset: OwnedRescueAsset, output: File): EncodedMoment {
        val first = asset.frames.first()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var codecStarted = false
        var codecStopped = false
        var muxerStopped = false
        var succeeded = false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, first.width, first.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer.setOrientationHint(asset.rotationDegrees)
            codec.start()
            codecStarted = true
            val drain = CodecDrain(codec, muxer)
            val pixels = IntArray(first.width * first.height)
            val i420 = I420Buffer(first.width, first.height)
            val firstTimestampUs = first.timestampUs

            asset.frames.forEach { frame ->
                decodeInto(frame, pixels)
                JpegToI420.convertArgb(pixels, frame.width, frame.height, i420)
                queueInput(codec, i420.bytes, frame.timestampUs - firstTimestampUs, flags = 0)
                drain.drain(endOfStream = false)
            }

            val durationUs = asset.requestEndUs - asset.requestStartUs
            queueInput(codec, EMPTY_INPUT, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain.drain(endOfStream = true)
            codec.stop()
            codecStopped = true
            check(drain.muxerStarted)
            muxer.stop()
            muxerStopped = true
            succeeded = true
            return EncodedMoment(
                stagingPath = output.absolutePath,
                durationUs = durationUs,
                width = first.width,
                height = first.height,
                rotationDegrees = asset.rotationDegrees,
                qualityTier = asset.qualityTier,
            )
        } finally {
            if (codecStarted && !codecStopped) runCatching { codec.stop() }
            codec.release()
            if (!muxerStopped) runCatching { muxer.stop() }
            muxer.release()
            if (!succeeded && output.exists()) output.delete()
        }
    }

    private fun queueInput(
        codec: MediaCodec,
        bytes: ByteArray,
        presentationTimeUs: Long,
        flags: Int,
    ) {
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) continue
            val input = checkNotNull(codec.getInputBuffer(inputIndex))
            input.clear()
            check(input.remaining() >= bytes.size) { "Encoder input buffer is too small" }
            input.put(bytes)
            codec.queueInputBuffer(inputIndex, 0, bytes.size, presentationTimeUs, flags)
            return
        }
    }

    private fun decodeInto(frame: RescueFrame, pixels: IntArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, bounds)
        require(bounds.outWidth == frame.width && bounds.outHeight == frame.height) {
            "JPEG dimensions do not match proxy metadata"
        }
        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(
                frame.jpeg,
                0,
                frame.jpeg.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ),
        ) { "Unable to decode proxy JPEG" }
        try {
            bitmap.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun validate(asset: OwnedRescueAsset) {
        require(asset.coverageComplete)
        require(asset.frames.isNotEmpty())
        require(asset.requestEndUs > asset.requestStartUs)
        require(asset.rotationDegrees in VALID_ROTATIONS)
        val first = asset.frames.first()
        require(first.width > 0 && first.height > 0)
        require(first.width % 2 == 0 && first.height % 2 == 0)
        asset.frames.forEach { frame ->
            require(frame.width == first.width && frame.height == first.height)
            require(frame.jpeg.isNotEmpty())
        }
        asset.frames.zipWithNext().forEach { (left, right) ->
            require(right.timestampUs > left.timestampUs)
        }
    }

    private companion object {
        val EMPTY_INPUT = ByteArray(0)
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
        const val BIT_RATE = 1_200_000
        const val TARGET_FPS = 8
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
