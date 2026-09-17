package ai.argvid.gen0.gimbal

import ai.argvid.gen0.domain.gimbal.EStopReason
import ai.argvid.gen0.domain.gimbal.GimbalCommandError
import ai.argvid.gen0.domain.gimbal.GimbalCommandException
import ai.argvid.gen0.domain.gimbal.GimbalConnectionState
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.gimbal.GimbalEvent
import ai.argvid.gen0.domain.gimbal.GimbalMode
import ai.argvid.gen0.domain.gimbal.GimbalMotionState
import ai.argvid.gen0.domain.gimbal.SemanticSetpoint
import ai.argvid.gen0.domain.time.MonotonicClock
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class F32cBleGimbalLinkTest {
    private val transport = FakeF32cBleTransport()
    private var nowUs = 1_000L
    private val clock = MonotonicClock { nowUs }

    @Test
    fun scanReturnsNonSimulatorCandidates() = runTest {
        val link = link(backgroundScope)
        val candidates = link.scan()
        assertEquals(listOf("F32C-Gimbal"), candidates.map { it.displayName })
        assertTrue(candidates.none { it.isSimulator })
    }

    @Test
    fun connectFailsWhenNoMotorAnswersTheBusScan() = runTest {
        val link = link(backgroundScope)
        transport.onScanAnswer = """{"event":"scan_result","ok":true,"motors":[]}"""
        assertRejects(GimbalCommandError.DeviceNotFound) { link.connect(GimbalDeviceId("AA")) }
        assertEquals(GimbalConnectionState.Disconnected, link.connection.value)
    }

    @Test
    fun connectLearnsAxisIdsAndReportsProtocolCapability() = runTest {
        val link = link(backgroundScope)
        val capability = link.connect(GimbalDeviceId("AA"))
        assertEquals(-180.0, capability.panRangeDeg.start, 0.0)
        assertEquals(180.0, capability.panRangeDeg.endInclusive, 0.0)
        assertEquals(-90.0, capability.tiltRangeDeg!!.start, 0.0)
        assertEquals(90.0, capability.tiltRangeDeg!!.endInclusive, 0.0)
        assertEquals("f32c-ble-bridge-1", capability.protocolVersion)
        assertEquals(GimbalConnectionState.Ready, link.connection.value)
    }

    @Test
    fun sendWritesOneMoveCommandAndEchoesTheSequence() = runTest {
        val link = connectedLink()
        val receipt = link.send(SemanticSetpoint(seq = 7u, panDeg = 5.0, tiltDeg = -2.5))
        assertEquals(7u.toUShort(), receipt.ackSeq)
        assertEquals(listOf("""{"cmd":"move","pan":5.0,"tilt":-2.5}"""), transport.commands.filter { it.contains("\"move\"") })
        assertEquals(GimbalMotionState.Moving, link.motion.value)
    }

    @Test
    fun sendRejectsTargetsOutsideTheProtocolRanges() = runTest {
        val link = connectedLink()
        assertRejects(GimbalCommandError.PanOutOfRange) { link.send(SemanticSetpoint(seq = 1u, panDeg = 200.0, tiltDeg = 0.0)) }
        assertRejects(GimbalCommandError.TiltOutOfRange) { link.send(SemanticSetpoint(seq = 2u, panDeg = 0.0, tiltDeg = 100.0)) }
        assertTrue(transport.commands.none { it.contains("\"move\"") })
    }

    @Test
    fun sendMapsFirmwareLimitRejection() = runTest {
        val link = connectedLink()
        transport.onNextCommandResult = """{"event":"cmd_result","ok":false,"msg":"tilt 轴限位 ±90°：拒绝越界目标角"}"""
        assertRejects(GimbalCommandError.TiltOutOfRange) { link.send(SemanticSetpoint(seq = 1u, panDeg = 0.0, tiltDeg = 0.0)) }
    }

    @Test
    fun missingCmdResultDegradesToTransportAcceptance() = runTest {
        val link = connectedLink()
        transport.onNextCommandResult = null
        val receipt = link.send(SemanticSetpoint(seq = 3u, panDeg = 1.0, tiltDeg = 0.0))
        assertEquals(3u.toUShort(), receipt.ackSeq)
    }

    @Test
    fun holdStopsBothAxesWithGuaranteedStopCommands() = runTest {
        val link = connectedLink()
        link.setMode(GimbalMode.Hold)
        assertEquals(
            listOf("""{"cmd":"jog","axis":"pan","dir":0}""", """{"cmd":"jog","axis":"tilt","dir":0}"""),
            transport.commands.filter { it.contains("\"jog\"") },
        )
        assertEquals(GimbalMotionState.Holding, link.motion.value)
    }

    @Test
    fun homeWritesOneCenterCommand() = runTest {
        val link = connectedLink()
        link.setMode(GimbalMode.Home)
        assertEquals(listOf("""{"cmd":"center"}"""), transport.commands.filter { it.contains("\"center\"") })
        assertEquals(GimbalMotionState.Moving, link.motion.value)
    }

    @Test
    fun emergencyStopStopsAxesLatchesFaultAndEmits() = runTest {
        val link = connectedLink()
        var stopped: GimbalEvent? = null
        backgroundScope.launch { link.events.collect { if (it is GimbalEvent.EmergencyStopped) stopped = it } }
        runCurrent()
        link.emergencyStop(EStopReason.UserRequested)
        testScheduler.advanceTimeBy(100)
        runCurrent()
        assertEquals(2, transport.commands.count { it.contains("\"jog\"") && it.contains("\"dir\":0") })
        assertEquals(GimbalMotionState.Fault, link.motion.value)
        assertTrue(link.telemetry.value.fault.orEmpty().startsWith("Emergency stop"))
        runCurrent()
        assertEquals(GimbalEvent.EmergencyStopped(EStopReason.UserRequested), stopped)
    }

    @Test
    fun unexpectedConnectionLossHoldsAndEmitsLostContact() = runTest {
        val link = connectedLink()
        var lostContact = false
        backgroundScope.launch { link.events.collect { if (it == GimbalEvent.LostContactHold) lostContact = true } }
        runCurrent()
        transport.loseConnection()
        runCurrent()
        assertEquals(GimbalConnectionState.Disconnected, link.connection.value)
        assertEquals(GimbalMotionState.Holding, link.motion.value)
        assertEquals("BLE connection lost", link.telemetry.value.fault)
        assertTrue(lostContact)
    }

    @Test
    fun measuredTelemetryComesOnlyFromQueryResults() = runTest {
        val link = connectedLink()
        emit("""{"event":"gimbal_state","pan":1,"tilt":2,"pan_angle":-180.0,"tilt_angle":-19.2}""")
        runCurrent()
        assertEquals(0.0, link.telemetry.value.panDeg, 0.0)
        emit("""{"event":"query_result","addr":1,"type":"total_angle","value":12.5,"text":"12.5°"}""")
        emit("""{"event":"query_result","addr":2,"type":"total_angle","value":-4.25,"text":"-4.25°"}""")
        runCurrent()
        assertEquals(12.5, link.telemetry.value.panDeg, 1e-9)
        assertEquals(-4.25, link.telemetry.value.tiltDeg, 1e-9)
    }

    @Test
    fun motionSettlesOnceBothMeasuredSpeedsReachZero() = runTest {
        val link = connectedLink()
        // Silence automatic speed answers so the settle transition is driven by the
        // explicit measured speeds below.
        transport.onSpeedQuery = null
        link.send(SemanticSetpoint(seq = 1u, panDeg = 5.0, tiltDeg = 0.0))
        assertEquals(GimbalMotionState.Moving, link.motion.value)
        emit("""{"event":"query_result","addr":1,"type":"speed","value":0.0}""")
        runCurrent()
        assertEquals(GimbalMotionState.Moving, link.motion.value)
        emit("""{"event":"query_result","addr":2,"type":"speed","value":0.0}""")
        runCurrent()
        assertEquals(GimbalMotionState.Idle, link.motion.value)
    }

    @Test
    fun secondCommandStaysMovingUntilFreshSpeedsArrive() = runTest {
        val link = connectedLink()
        transport.onSpeedQuery = null
        link.send(SemanticSetpoint(seq = 1u, panDeg = 5.0, tiltDeg = 0.0))
        emit("""{"event":"query_result","addr":1,"type":"speed","value":0.0}""")
        emit("""{"event":"query_result","addr":2,"type":"speed","value":0.0}""")
        runCurrent()
        assertEquals(GimbalMotionState.Idle, link.motion.value)

        // A second move must ignore the pre-command 0/0 readings: only speed
        // measurements taken after the command may settle it.
        link.send(SemanticSetpoint(seq = 2u, panDeg = -5.0, tiltDeg = 0.0))
        assertEquals(GimbalMotionState.Moving, link.motion.value)
        emit("""{"event":"query_result","addr":1,"type":"total_angle","value":5.0}""")
        runCurrent()
        assertEquals(GimbalMotionState.Moving, link.motion.value)
        emit("""{"event":"query_result","addr":1,"type":"speed","value":0.0}""")
        emit("""{"event":"query_result","addr":2,"type":"speed","value":0.0}""")
        runCurrent()
        assertEquals(GimbalMotionState.Idle, link.motion.value)
    }

    @Test
    fun queryFailureNoiseDoesNotRejectAnExecutingMove() = runTest {
        val link = connectedLink()
        // Observed on hardware: a failed bus query answers with a failed
        // cmd_result that must not reject an in-flight move.
        transport.onNextCommandResult = """{"event":"cmd_result","ok":false,"msg":"查询失败: 帧太短 (0 字节)"}"""
        val receipt = link.send(SemanticSetpoint(seq = 9u, panDeg = 5.0, tiltDeg = 0.0))
        assertEquals(9u.toUShort(), receipt.ackSeq)
        assertEquals(GimbalMotionState.Moving, link.motion.value)
    }

    @Test
    fun firmwareRejectionStopsBothAxesBeforeSurfacing() = runTest {
        val link = connectedLink()
        transport.onNextCommandResult = """{"event":"cmd_result","ok":false,"msg":"tilt 轴限位 ±90°：拒绝越界目标角"}"""
        assertRejects(GimbalCommandError.TiltOutOfRange) { link.send(SemanticSetpoint(seq = 1u, panDeg = 0.0, tiltDeg = 0.0)) }
        val jogs = transport.commands.filter { it.contains("\"jog\"") && it.contains("\"dir\":0") }
        assertEquals("defensive stop must be issued before surfacing the rejection", 2, jogs.size)
    }

    @Test
    fun connectRejectsWhenTheFirmwareNeverReportsAxisRoles() = runTest {
        val link = link(backgroundScope)
        transport.emitGimbalStateAfterScan = false
        assertRejects(GimbalCommandError.DeviceNotFound) { link.connect(GimbalDeviceId("AA")) }
        assertEquals(GimbalConnectionState.Disconnected, link.connection.value)
    }

    @Test
    fun connectRefusesReadyWithoutAMeasuredBaseline() = runTest {
        val link = link(backgroundScope)
        transport.onAngleQuery = null
        assertRejects(GimbalCommandError.DeviceNotFound) { link.connect(GimbalDeviceId("AA")) }
        assertEquals(GimbalConnectionState.Disconnected, link.connection.value)
    }

    @Test
    fun connectRefusesMeasuredTiltOutsideSafetyRange() = runTest {
        val link = link(backgroundScope)
        transport.onAngleQuery = { addr ->
            val value = if (addr == 2) 227.0 else 0.0
            """{"event":"query_result","addr":$addr,"type":"total_angle","value":$value}"""
        }

        assertRejects(GimbalCommandError.TiltOutOfRange) { link.connect(GimbalDeviceId("AA")) }
        assertEquals(GimbalConnectionState.Disconnected, link.connection.value)
    }

    @Test
    fun sendRejectsSpeedAndDeadlineOutsideTheCapabilityContract() = runTest {
        val link = connectedLink()
        assertRejects(GimbalCommandError.SpeedOutOfRange) {
            link.send(SemanticSetpoint(seq = 1u, panDeg = 5.0, tiltDeg = 0.0, maxDegreesPerSecond = 31.0))
        }
        assertRejects(GimbalCommandError.DeadlineInvalid) {
            link.send(SemanticSetpoint(seq = 2u, panDeg = 5.0, tiltDeg = 0.0, deadlineMs = 401))
        }
    }

    @Test
    fun emergencyStopLatchesCommandsUntilAFreshConnect() = runTest {
        val link = connectedLink()
        link.emergencyStop(EStopReason.UserRequested)
        testScheduler.advanceTimeBy(100)
        runCurrent()
        assertEquals(GimbalMotionState.Fault, link.motion.value)
        assertRejects(GimbalCommandError.NotReady) { link.send(SemanticSetpoint(seq = 1u, panDeg = 5.0, tiltDeg = 0.0)) }
        assertRejects(GimbalCommandError.NotReady) { link.setMode(GimbalMode.Hold) }
        link.disconnect()
        link.connect(GimbalDeviceId("AA"))
        transport.commands.clear()

        val receipt = link.send(SemanticSetpoint(seq = 4u, panDeg = 5.0, tiltDeg = 0.0))
        assertEquals(4u.toUShort(), receipt.ackSeq)
    }

    @Test
    fun telemetryPollerQueriesAnglesAndSpeedsWhileReady() = runTest {
        val link = connectedLink()
        advanceTimeBy(5_000)
        runCurrent()
        val queries = transport.commands.filter { it.contains("\"query\"") }
        assertTrue("poller issued queries: $queries", queries.any { it.contains("total_angle") && it.contains("addr\":1") })
        assertTrue(queries.any { it.contains("total_angle") && it.contains("addr\":2") })
        assertTrue(queries.any { it.contains("speed") })
    }

    @Test
    fun pollerRunsFasterWhileMovingThanWhileIdle() = runTest {
        val link = connectedLink()
        transport.onSpeedQuery = null
        link.send(SemanticSetpoint(seq = 1u, panDeg = 5.0, tiltDeg = 0.0))
        transport.commands.clear()
        advanceTimeBy(2_000)
        runCurrent()
        val movingQueries = transport.commands.count { it.contains("\"query\"") }

        emit("""{"event":"query_result","addr":1,"type":"speed","value":0.0}""")
        emit("""{"event":"query_result","addr":2,"type":"speed","value":0.0}""")
        runCurrent()
        transport.commands.clear()
        advanceTimeBy(2_000)
        runCurrent()
        val idleQueries = transport.commands.count { it.contains("\"query\"") }

        assertTrue("moving=$movingQueries idle=$idleQueries", movingQueries > idleQueries)
    }

    private fun link(scope: CoroutineScope) = F32cBleGimbalLink(transport, scope, clock)

    private suspend fun TestScope.connectedLink(): F32cBleGimbalLink {
        val link = link(backgroundScope)
        link.connect(GimbalDeviceId("AA"))
        runCurrent()
        transport.commands.clear()
        return link
    }

    /** Pushes one complete FF04 payload through the notification bus. */
    private fun emit(payload: String) {
        transport.emit(payload)
    }

    private suspend fun assertRejects(expected: GimbalCommandError, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: GimbalCommandException) {
            assertEquals(expected, error.reason)
            return
        }
        throw AssertionError("expected GimbalCommandException($expected)")
    }


    // ---- 2026-09-16 PR #7 review blockers: A1 honest e-stop, A2 config-only speed, A3 per-axis freshness ----

    @Test
    fun emergencyStopReportsPanStopDeliveryFailureAndFallsBackToDisconnect() = runTest {
        val link = connectedLink()
        transport.failWriteFor("""{"cmd":"jog","axis":"pan","dir":0}""")
        link.emergencyStop(EStopReason.UserRequested)
        testScheduler.advanceTimeBy(100)
        runCurrent()
        val jogs = transport.commands.filter { it.contains("jog") && it.contains("dir\":0") }
        assertTrue("tilt stop must still be attempted", jogs.any { it.contains("tilt") })
        assertTrue(
            "fault must name the failed delivery",
            link.telemetry.value.fault.orEmpty().contains("停止写入未送达"),
        )
        assertEquals(GimbalConnectionState.Disconnected, link.connection.value)
        assertEquals(GimbalMotionState.Fault, link.motion.value)
    }

    @Test
    fun emergencyStopSuccessMessageNeverClaimsPhysicalStopConfirmation() = runTest {
        val link = connectedLink()
        link.emergencyStop(EStopReason.UserRequested)
        assertTrue(
            "fault must say sent-not-confirmed",
            link.telemetry.value.fault.orEmpty().contains("到位未确认"),
        )
    }

    @Test
    fun setSpeedUsesConfigOnlySetPositionSpeedNeverRawSetSpeed() = runTest {
        val link = connectedLink()
        link.setSpeed(60)
        val speedWrites = transport.commands.filter { it.contains("speed") }
        assertTrue(
            "all speed writes must be config-only set_position_speed: $speedWrites",
            speedWrites.all { it.contains("set_position_speed") },
        )
    }

    @Test
    fun setSpeedSurfacesFirmwareRejectionOfUnknownCommand() = runTest {
        val link = connectedLink()
        transport.onNextCommandResult =
            """{"event":"cmd_result","ok":false,"msg":"未知命令: set_position_speed"}"""
        assertRejects(GimbalCommandError.NotReady) { link.setSpeed(60) }
    }

    @Test
    fun singleAxisRefreshDoesNotMakeStaleOtherAxisLookFresh() = runTest {
        val link = connectedLink()
        runCurrent()
        // Pan keeps refreshing; tilt stops after connect. After >3s of only-pan
        // updates the pose timestamp must reflect the stale tilt, not fresh pan.
        nowUs += 3_600_000
        emit("""{"event":"query_result","addr":1,"type":"total_angle","value":10.0}""")
        runCurrent()
        val telemetry = link.telemetry.value
        assertTrue(
            "pose measuredAtMs must be the oldest axis (tilt stale >=3s), was ${telemetry.measuredAtMs} vs now ${nowUs / 1000}",
            nowUs / 1_000 - telemetry.measuredAtMs >= 3_000,
        )
    }

    internal class FakeF32cBleTransport : F32cBleTransport {
        val commands = mutableListOf<String>()

        // Replay buffers keep fakes deterministic when the link's collectors subscribe
        // after an emit, which the real transport avoids by always subscribing first.
        val notificationsBus = MutableSharedFlow<F32cBleNotification>(replay = 64)
        private val connectionLostBus = MutableSharedFlow<Unit>(replay = 1)
        var onNextCommandResult: String? = DEFAULT_OK_RESULT
        var failWritePatterns: List<String> = emptyList()
        var onScanAnswer: String? = null
        var emitGimbalStateAfterScan: Boolean = true

        override val notifications: SharedFlow<F32cBleNotification> = notificationsBus
        override val connectionLost: SharedFlow<Unit> = connectionLostBus

        /** Answer builder for total_angle queries; null stays silent. */
        var onAngleQuery: ((Int) -> String)? = { addr ->
            """{"event":"query_result","addr":$addr,"type":"total_angle","value":0.0,"text":"0°"}"""
        }

        /** Answer builder for speed queries; null stays silent (keeps settle tests deterministic). */
        var onSpeedQuery: ((Int) -> String)? = { addr ->
            """{"event":"query_result","addr":$addr,"type":"speed","value":0.0}"""
        }

        override suspend fun scan(timeoutMs: Long): List<F32cBleDevice> =
            listOf(F32cBleDevice("AA", "F32C-Gimbal"))

        override suspend fun connect(id: String, timeoutMs: Long) = Unit

        override suspend fun writeCommand(json: String): F32cWriteReceipt {
            if (failWritePatterns.any { json.contains(it) }) {
                throw IllegalStateException("injected write failure: $json")
            }
            commands += json
            when {
                json.contains("\"scan\"") -> {
                    emit(
                        onScanAnswer
                            ?: """{"event":"scan_result","ok":true,"motors":[{"id":1,"volt":12.0},{"id":2,"volt":12.0}]}""",
                    )
                    // The firmware pushes the configured axis ids right after a scan.
                    if (emitGimbalStateAfterScan) {
                        emit("""{"event":"gimbal_state","pan":1,"tilt":2,"pan_angle":0.0,"tilt_angle":0.0}""")
                    }
                }
                json.contains("total_angle") -> {
                    val addr = Regex("\"addr\":(\\d+)").find(json)!!.groupValues[1].toInt()
                    onAngleQuery?.invoke(addr)?.let { emit(it) }
                }
                json.contains("\"query\"") -> {
                    val addr = Regex("\"addr\":(\\d+)").find(json)!!.groupValues[1].toInt()
                    onSpeedQuery?.invoke(addr)?.let { emit(it) }
                }
                else -> onNextCommandResult?.let { emit(it) }
            }
            return F32cWriteReceipt(transportAccepted = true)
        }

        override suspend fun disconnect() = Unit

        fun loseConnection() {
            connectionLostBus.tryEmit(Unit)
        }

        fun failWriteFor(pattern: String) {
            failWritePatterns = failWritePatterns + pattern
        }

        fun emit(payload: String) {
            notificationsBus.tryEmit(
                F32cBleNotification(
                    F32cBleContract.responseUuid,
                    payload.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        }

        private companion object {
            val DEFAULT_OK_RESULT = """{"event":"cmd_result","ok":true,"msg":"命令已发送"}"""
        }
    }
}
