package ai.argvid.gen0.gimbal

import ai.argvid.gen0.domain.gimbal.CommandResult
import ai.argvid.gen0.domain.gimbal.EStopReason
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalController
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.time.MonotonicClock
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 third-round regression: production bridge + domain controller.
 * Caller 400ms timeout must not prevent dual-axis stop / disconnect fallback.
 * Stop runs under bounded NonCancellable; controller may still report TimedOut.
 */
class F32cEStopTimeoutTest {
    @Test
    fun callerTimeoutStillAttemptsBothAxes() = runTest {
        val transport = PanDelayedFakeTransport(panDelayMs = 500)
        val link = F32cBleGimbalLink(transport, backgroundScope, MonotonicClock { 1_000 })
        link.connect(GimbalDeviceId("AA"))
        runCurrent()
        assertEquals(GimbalConnectionState.Ready, link.connection.value)

        // Accept a move first so the probe matches the review scenario.
        transport.commands.clear()
        val controller = GimbalController(link, commandTimeoutMs = 400)
        val pending = async { controller.emergencyStop() }

        // Domain budget fires while pan stop is still delayed.
        advanceTimeBy(400)
        runCurrent()
        // Finish pan delay + tilt stop inside NonCancellable budget.
        advanceTimeBy(600)
        runCurrent()

        val result = pending.await()
        // Caller wait timed out; physical stop must still have run.
        assertEquals("controller wait is 400ms; pan stop is 500ms", CommandResult.TimedOut, result)
        assertTrue(
            "pan stop write must be attempted (commands: ${transport.commands})",
            transport.commands.any { it.contains("\"pan\"") && it.contains("\"dir\":0") },
        )
        assertTrue(
            "tilt stop write must also be attempted",
            transport.commands.any { it.contains("\"tilt\"") && it.contains("\"dir\":0") },
        )
        assertEquals(GimbalMotionState.Fault, link.motion.value)
        assertTrue(link.telemetry.value.fault.orEmpty().isNotEmpty())
        // Successful dual-axis stop keeps Ready; no silent Ready+no-tilt failure mode.
        assertTrue(
            "must not leave Ready without any tilt stop after caller timeout",
            link.connection.value != GimbalConnectionState.Ready ||
                transport.commands.any { it.contains("\"tilt\"") && it.contains("\"dir\":0") },
        )
    }

    @Test
    fun panWriteFailureStillAttemptsTiltAndDisconnects() = runTest {
        val transport = PanDelayedFakeTransport(panDelayMs = 0, panFail = true)
        val link = F32cBleGimbalLink(transport, backgroundScope, MonotonicClock { 1_000 })
        link.connect(GimbalDeviceId("AA"))
        runCurrent()

        link.emergencyStop(EStopReason.UserRequested)
        runCurrent()

        assertTrue(
            "tilt stop must still be attempted",
            transport.commands.any { it.contains("\"tilt\"") && it.contains("\"dir\":0") },
        )
        assertEquals(
            "connection must transition to Disconnected after stop failure",
            GimbalConnectionState.Disconnected,
            link.connection.value,
        )
        assertTrue(
            "fault must report delivery failure",
            link.telemetry.value.fault.orEmpty().contains("停止写入未送达"),
        )
    }

    @Test
    fun disconnectFallbackFailureIsHonest() = runTest {
        val transport = PanDelayedFakeTransport(panDelayMs = 0, panFail = true, disconnectFail = true)
        val link = F32cBleGimbalLink(transport, backgroundScope, MonotonicClock { 1_000 })
        link.connect(GimbalDeviceId("AA"))
        runCurrent()

        link.emergencyStop(EStopReason.UserRequested)
        runCurrent()

        assertTrue(transport.commands.any { it.contains("\"tilt\"") && it.contains("\"dir\":0") })
        assertTrue(
            "must not claim disconnect succeeded",
            link.telemetry.value.fault.orEmpty().contains("断连兜底也失败"),
        )
        assertFalse(
            "must not mark Disconnected when disconnect failed",
            link.connection.value == GimbalConnectionState.Disconnected &&
                !link.telemetry.value.fault.orEmpty().contains("断连兜底也失败"),
        )
        // Connection stays non-Disconnected when transport.disconnect throws.
        assertEquals(GimbalConnectionState.Ready, link.connection.value)
    }

    private class PanDelayedFakeTransport(
        private val panDelayMs: Long,
        private val panFail: Boolean = false,
        private val disconnectFail: Boolean = false,
    ) : F32cBleTransport {
        val commands = mutableListOf<String>()
        private var disconnectCalls = 0
        val notificationsBus = MutableSharedFlow<F32cBleNotification>(replay = 64)
        private val connectionLostBus = MutableSharedFlow<Unit>(replay = 1)

        override val notifications: SharedFlow<F32cBleNotification> = notificationsBus
        override val connectionLost: SharedFlow<Unit> = connectionLostBus

        override suspend fun scan(timeoutMs: Long) = listOf(F32cBleDevice("AA", "F32C-Gimbal"))
        override suspend fun connect(id: String, timeoutMs: Long) = Unit

        override suspend fun writeCommand(json: String): F32cWriteReceipt {
            commands += json
            when {
                json.contains("\"scan\"") -> {
                    emit("""{"event":"scan_result","ok":true,"motors":[{"id":1,"volt":12.0},{"id":2,"volt":12.0}]}""")
                    emit("""{"event":"gimbal_state","pan":1,"tilt":2,"pan_angle":0.0,"tilt_angle":0.0}""")
                }
                json.contains("total_angle") && json.contains("\"addr\":1") ->
                    emit("""{"event":"query_result","addr":1,"type":"total_angle","value":0.0}""")
                json.contains("total_angle") && json.contains("\"addr\":2") ->
                    emit("""{"event":"query_result","addr":2,"type":"total_angle","value":0.0}""")
                json.contains("\"query\"") && json.contains("\"addr\":1") ->
                    emit("""{"event":"query_result","addr":1,"type":"speed","value":0.0}""")
                json.contains("\"query\"") && json.contains("\"addr\":2") ->
                    emit("""{"event":"query_result","addr":2,"type":"speed","value":0.0}""")
            }
            if (json.contains("\"jog\"") && json.contains("\"pan\"") && json.contains("\"dir\":0")) {
                if (panDelayMs > 0) delay(panDelayMs)
                if (panFail) throw IllegalStateException("pan write failed")
            }
            return F32cWriteReceipt(transportAccepted = true)
        }

        override suspend fun disconnect() {
            disconnectCalls += 1
            if (disconnectFail) throw IllegalStateException("disconnect failed")
        }

        fun emit(payload: String) {
            notificationsBus.tryEmit(
                F32cBleNotification(
                    F32cBleContract.responseUuid,
                    payload.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        }
    }
}