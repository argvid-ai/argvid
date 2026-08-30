package ai.argvid.gen0.media.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JpegToI420Test {
    @Test
    fun convertsBt601LimitedRangeReferenceColors() {
        val references = listOf(
            ReferenceColor(0xff000000.toInt(), 16, 128, 128),
            ReferenceColor(0xffffffff.toInt(), 235, 128, 128),
            ReferenceColor(0xffff0000.toInt(), 82, 90, 240),
            ReferenceColor(0xff00ff00.toInt(), 144, 54, 34),
            ReferenceColor(0xff0000ff.toInt(), 41, 240, 110),
        )

        references.forEach { reference ->
            val output = I420Buffer(width = 2, height = 2)
            JpegToI420.convertArgb(
                argb = IntArray(4) { reference.argb },
                width = 2,
                height = 2,
                output = output,
            )

            assertArrayEquals(
                byteArrayOf(
                    reference.y.toByte(), reference.y.toByte(), reference.y.toByte(), reference.y.toByte(),
                    reference.u.toByte(), reference.v.toByte(),
                ),
                output.bytes,
            )
        }
    }

    @Test
    fun averagesChromaAcrossEachTwoByTwoBlock() {
        val output = I420Buffer(width = 2, height = 2)

        JpegToI420.convertArgb(
            argb = intArrayOf(
                0xffff0000.toInt(), 0xff00ff00.toInt(),
                0xff0000ff.toInt(), 0xffffffff.toInt(),
            ),
            width = 2,
            height = 2,
            output = output,
        )

        assertArrayEquals(byteArrayOf(82, 144.toByte(), 41, 235.toByte(), 128.toByte(), 128.toByte()), output.bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddDimensions() {
        I420Buffer(width = 3, height = 2)
    }

    private data class ReferenceColor(
        val argb: Int,
        val y: Int,
        val u: Int,
        val v: Int,
    )
}
