package ai.argvid.gen0.capture

data class ProxyConfiguration(
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val retentionUs: Long = 15_000_000,
    val maxLogicalBytes: Long = 32L * 1024 * 1024,
) {
    init {
        require(width > 0)
        require(height > 0)
        require(targetFps > 0)
        require(retentionUs > 0)
        require(maxLogicalBytes > 0)
    }

    val targetIntervalUs: Long = 1_000_000L / targetFps

    companion object {
        fun p540() = ProxyConfiguration(width = 960, height = 540, targetFps = 8)
    }
}
