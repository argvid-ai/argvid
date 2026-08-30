package ai.argvid.gen0.capture

data class ProxyFrame(
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
)
