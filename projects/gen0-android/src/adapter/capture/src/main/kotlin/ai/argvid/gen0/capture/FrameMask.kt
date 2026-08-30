package ai.argvid.gen0.capture

data class FrameMask(
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
}
