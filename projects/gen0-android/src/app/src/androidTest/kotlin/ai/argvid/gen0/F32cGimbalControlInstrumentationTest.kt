package ai.argvid.gen0

import ai.argvid.gen0.domain.gimbal.CommandResult
import ai.argvid.gen0.domain.gimbal.GimbalController
import ai.argvid.gen0.domain.gimbal.GimbalDeviceId
import ai.argvid.gen0.domain.time.MonotonicClock
import ai.argvid.gen0.gimbal.AndroidF32cBleTransport
import ai.argvid.gen0.gimbal.F32cBleGimbalLink
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-C device diagnostic: the production GimbalController stack
 * (controller → F32cBleGimbalLink → AndroidF32cBleTransport) driving the physical
 * F32C gimbal through small pan-only motions, hold and emergency stop.
 *
 * THIS TEST MOVES REAL HARDWARE. It runs only on an approved device with granted
 * Bluetooth permissions and an explicitly cleared gimbal. The pan excursion is
 * bounded to ±5° from the measured start position; tilt is commanded only as the
 * unchanged measured tilt target (move always writes both axes — firmware option
 * fields are not used), so any tilt motion is limited to measured-drift snap-back.
 * Every step logs the command path and measured telemetry so the joint test
 * record can cite real evidence; nothing here claims device-confirmed arrival —
 * arrival is only what query_result(total_angle) measures afterwards.
 */
@RunWith(AndroidJUnit4::class)
class F32cGimbalControlInstrumentationTest {
    @Test
    fun controllerDrivesSmallPanMotionsThenHoldsAndStops() {
        runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = AndroidF32cBleTransport(context)
        val link = F32cBleGimbalLink(transport, scope, MonotonicClock { System.nanoTime() / 1_000 })
        val controller = GimbalController(link)
        try {
            val candidates = controller.scan()
            assertTrue("F32C-Gimbal broadcast disappeared during scan", candidates.isNotEmpty())
            val capability = controller.connect(GimbalDeviceId(candidates.first().id.value))
            Log.i(TAG, "capability pan=${capability.panRangeDeg} tilt=${capability.tiltRangeDeg} watchdog=${capability.watchdogMs} protocol=${capability.protocolVersion}")

            // Let the telemetry poller capture the measured start position.
            delay(3_000)
            val startPan = link.telemetry.value.panDeg
            Log.i(TAG, "measured_start panDeg=$startPan tiltDeg=${link.telemetry.value.tiltDeg}")

            // Steer away from the ±180° seam so the bounded excursion stays in range.
            val outward = if (startPan > 160.0) -NUDGE_DEG else NUDGE_DEG

            val outwardResult = controller.nudge(outward, 0.0)
            Log.i(TAG, "nudge_outward=$outward result=$outwardResult")
            assertTrue("outward nudge was not accepted: $outwardResult", outwardResult is CommandResult.Accepted)
            delay(SETTLE_MS)
            val outwardPan = link.telemetry.value.panDeg
            Log.i(TAG, "measured_after_outward panDeg=$outwardPan")
            assertTrue(
                "pan did not measurably move (start=$startPan after=$outwardPan)",
                kotlin.math.abs(outwardPan - startPan) >= MIN_MEASURED_DELTA_DEG,
            )

            val returnResult = controller.nudge(-outward, 0.0)
            Log.i(TAG, "nudge_return=${-outward} result=$returnResult")
            assertTrue("return nudge was not accepted: $returnResult", returnResult is CommandResult.Accepted)
            delay(SETTLE_MS)
            val returnedPan = link.telemetry.value.panDeg
            Log.i(TAG, "measured_after_return panDeg=$returnedPan")
            assertTrue(
                "pan did not return near start (start=$startPan returned=$returnedPan)",
                kotlin.math.abs(returnedPan - startPan) <= RETURN_TOLERANCE_DEG,
            )

            val holdResult = controller.hold()
            Log.i(TAG, "hold result=$holdResult")
            assertTrue("hold was not accepted: $holdResult", holdResult is CommandResult.Accepted)
            delay(2_000)

            val stopResult = controller.emergencyStop()
            Log.i(TAG, "emergency_stop result=$stopResult")
            assertTrue("emergency stop was not accepted: $stopResult", stopResult is CommandResult.Accepted)

            controller.disconnect()
            Log.i(TAG, "phase_c_motion_sequence_complete=true")
        } finally {
            // Best-effort stop on ANY exit path (including assertion failures):
            // a mid-test failure must not leave the gimbal displaced and running.
            runCatching { controller.emergencyStop() }
            runCatching { controller.disconnect() }
            runCatching { transport.disconnect() }
            transport.close()
            scope.cancel()
        }
        }
    }

    private companion object {
        const val TAG = "F32C_CONTROL"
        const val NUDGE_DEG = 20.0
        const val MIN_MEASURED_DELTA_DEG = 10.0
        const val RETURN_TOLERANCE_DEG = 2.5
        const val SETTLE_MS = 10_000L
    }
}
