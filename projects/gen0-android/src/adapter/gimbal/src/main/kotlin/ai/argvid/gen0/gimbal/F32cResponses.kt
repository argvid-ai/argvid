package ai.argvid.gen0.gimbal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One motor address reported by the firmware bus scan. [volt] is null when the firmware omitted it. */
data class F32cScanMotor(
    val id: Int,
    val volt: Double?,
)

/** Parsed FF04 `scan_result` event: the read-only outcome of a firmware bus scan. */
data class F32cScanResult(
    val ok: Boolean,
    val motors: List<F32cScanMotor>,
)

/** Parsed FF04 `query_result` event: one read-only telemetry answer from one motor. */
data class F32cQueryResult(
    val addr: Int,
    val type: String,
    val value: Double?,
    val text: String?,
)

/**
 * Parsed FF04 `gimbal_state` / FF02 `sys_status` event: the firmware's configured
 * axis motor ids plus its latest *target* angles. Targets must never be presented
 * as measured position; measured angles come only from `query_result`.
 */
data class F32cGimbalStatus(
    val panId: Int,
    val tiltId: Int,
    val panAngleDeg: Double?,
    val tiltAngleDeg: Double?,
)

/**
 * Project-local decoder for F32C FF04 payloads. It reads only the fixed `scan_result`
 * shape the firmware emits; every other event or malformed payload decodes to null so a
 * read-only collector can keep waiting instead of failing the discovery chain.
 */
object F32cResponses {
    const val scanResultEvent = "scan_result"
    const val queryResultEvent = "query_result"
    const val gimbalStateEvent = "gimbal_state"
    const val sysStatusEvent = "sys_status"
    const val cmdResultEvent = "cmd_result"

    private const val MIN_MOTOR_ID = 1
    private const val MAX_MOTOR_ID = 127

    fun scanResult(payload: String): F32cScanResult? {
        val root = parseEvent(payload, scanResultEvent) ?: return null
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val motors = root["motors"] as? JsonArray ?: return null
        val decoded = buildList {
            for (element in motors) {
                val motor = element as? JsonObject ?: continue
                val id = (motor["id"] as? JsonPrimitive)?.intOrNull ?: continue
                if (id !in MIN_MOTOR_ID..MAX_MOTOR_ID) continue
                add(F32cScanMotor(id, (motor["volt"] as? JsonPrimitive)?.doubleOrNull))
            }
        }
        return F32cScanResult(ok, decoded)
    }

    fun queryResult(payload: String): F32cQueryResult? {
        val root = parseEvent(payload, queryResultEvent) ?: return null
        val addr = (root["addr"] as? JsonPrimitive)?.intOrNull ?: return null
        val type = (root["type"] as? JsonPrimitive)?.content ?: return null
        return F32cQueryResult(
            addr = addr,
            type = type,
            value = (root["value"] as? JsonPrimitive)?.doubleOrNull,
            text = (root["text"] as? JsonPrimitive)?.content,
        )
    }

    /** Decodes the axis-configuration/target event pushed as `gimbal_state` and `sys_status`. */
    fun gimbalStatus(payload: String): F32cGimbalStatus? {
        val root = parseEvent(payload, gimbalStateEvent) ?: parseEvent(payload, sysStatusEvent) ?: return null
        val panId = (root["pan"] as? JsonPrimitive)?.intOrNull ?: return null
        val tiltId = (root["tilt"] as? JsonPrimitive)?.intOrNull ?: return null
        if (panId !in MIN_MOTOR_ID..MAX_MOTOR_ID || tiltId !in MIN_MOTOR_ID..MAX_MOTOR_ID || panId == tiltId) {
            return null
        }
        return F32cGimbalStatus(
            panId = panId,
            tiltId = tiltId,
            panAngleDeg = (root["pan_angle"] as? JsonPrimitive)?.doubleOrNull,
            tiltAngleDeg = (root["tilt_angle"] as? JsonPrimitive)?.doubleOrNull,
        )
    }

    /** Decodes `cmd_result` into its ok flag and message, used to detect firmware-side rejection. */
    fun commandResult(payload: String): Pair<Boolean, String>? {
        val root = parseEvent(payload, cmdResultEvent) ?: return null
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val msg = (root["msg"] as? JsonPrimitive)?.content
        return ok to (msg ?: "")
    }

    private fun parseEvent(payload: String, event: String): JsonObject? {
        val root = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        if ((root["event"] as? JsonPrimitive)?.content != event) return null
        return root
    }
}
