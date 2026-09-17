package ai.argvid.gen0.gimbal

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Project-local F32C BLE endpoints and wire constraints. */
object F32cBleContract {
    val serviceUuid: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    val wifiWriteUuid: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val statusUuid: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val commandUuid: UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
    val responseUuid: UUID = UUID.fromString("0000ff04-0000-1000-8000-00805f9b34fb")

    const val advertisedName = "F32C-Gimbal"
    const val advertisedPrefix = "F32C"
    const val requestedMtu = 247

    // The firmware's FF03 handler drops any JSON whose length reaches its 240-byte
    // buffer (json.length() >= sizeof(json[240])), so the largest accepted command
    // is 239 UTF-8 bytes, one below the 240 documented in the firmware API notes.
    const val maxCommandBytes = 239
    const val maxReassembledBytes = 8192
    const val fragmentTimeoutMs = 3_000L

    fun isAdvertisedNameAccepted(name: String?): Boolean =
        name == advertisedName || name?.startsWith(advertisedPrefix) == true

    fun commandJson(json: String): ByteArray {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxCommandBytes) {
            "F32C command exceeds $maxCommandBytes UTF-8 bytes"
        }
        return bytes
    }
}
