package ai.argvid.gen0.media.codec

class I420Buffer(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
        require(width % 2 == 0 && height % 2 == 0)
    }

    val bytes = ByteArray(width * height * 3 / 2)
}

object JpegToI420 {
    fun convertArgb(
        argb: IntArray,
        width: Int,
        height: Int,
        output: I420Buffer,
    ) {
        require(width == output.width && height == output.height)
        require(argb.size == width * height)

        val ySize = width * height
        val uOffset = ySize
        val vOffset = ySize + ySize / 4
        var chromaIndex = 0

        for (blockY in 0 until height step 2) {
            for (blockX in 0 until width step 2) {
                var uTotal = 0
                var vTotal = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val pixelIndex = (blockY + dy) * width + blockX + dx
                        val pixel = argb[pixelIndex]
                        val red = pixel shr 16 and 0xff
                        val green = pixel shr 8 and 0xff
                        val blue = pixel and 0xff
                        output.bytes[pixelIndex] = luma(red, green, blue).toByte()
                        uTotal += chromaU(red, green, blue)
                        vTotal += chromaV(red, green, blue)
                    }
                }
                output.bytes[uOffset + chromaIndex] = ((uTotal + 2) / 4).toByte()
                output.bytes[vOffset + chromaIndex] = ((vTotal + 2) / 4).toByte()
                chromaIndex += 1
            }
        }
    }

    private fun luma(red: Int, green: Int, blue: Int): Int =
        (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16).coerceIn(16, 235)

    private fun chromaU(red: Int, green: Int, blue: Int): Int =
        (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128).coerceIn(16, 240)

    private fun chromaV(red: Int, green: Int, blue: Int): Int =
        (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128).coerceIn(16, 240)
}
