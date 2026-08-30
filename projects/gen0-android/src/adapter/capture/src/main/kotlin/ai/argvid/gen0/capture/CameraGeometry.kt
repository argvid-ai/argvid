package ai.argvid.gen0.capture

data class NormalizedCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right)
        require(top < bottom)
    }

    companion object {
        val Full = NormalizedCrop(0f, 0f, 1f, 1f)
    }
}

data class CameraGeometry(
    val rotationDegrees: Int,
    val crop: NormalizedCrop = NormalizedCrop.Full,
)

internal fun mapCropAfterRotation(
    sourceCrop: NormalizedCrop,
    rotationDegrees: Int,
): NormalizedCrop = when (rotationDegrees) {
    0 -> sourceCrop
    90 -> NormalizedCrop(
        left = 1f - sourceCrop.bottom,
        top = sourceCrop.left,
        right = 1f - sourceCrop.top,
        bottom = sourceCrop.right,
    )
    180 -> NormalizedCrop(
        left = 1f - sourceCrop.right,
        top = 1f - sourceCrop.bottom,
        right = 1f - sourceCrop.left,
        bottom = 1f - sourceCrop.top,
    )
    270 -> NormalizedCrop(
        left = sourceCrop.top,
        top = 1f - sourceCrop.right,
        right = sourceCrop.bottom,
        bottom = 1f - sourceCrop.left,
    )
    else -> throw MaskMappingException()
}
