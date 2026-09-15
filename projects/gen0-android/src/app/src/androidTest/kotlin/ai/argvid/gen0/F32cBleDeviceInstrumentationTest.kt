package ai.argvid.gen0

import ai.argvid.gen0.gimbal.AndroidF32cBleTransport
import ai.argvid.gen0.gimbal.F32cFragmentReassembler
import ai.argvid.gen0.gimbal.F32cQueryResult
import ai.argvid.gen0.gimbal.F32cResponses
import ai.argvid.gen0.gimbal.F32cScanResult
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class F32cBleDeviceInstrumentationTest {
    @Test
    fun gattAndScanDrivenReadOnlyVoltageQuery() {
        runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
        )

        val transport = AndroidF32cBleTransport(context)
        var collector: Job? = null
        val payloads = mutableListOf<String>()
        val queryResults = mutableListOf<F32cQueryResult>()
        try {
            val devices = transport.scan(timeoutMs = 8_000)
            assertFalse("F32C-Gimbal broadcast disappeared during instrumentation scan", devices.isEmpty())
            val device = devices.first()
            Log.i(TAG, "broadcast_candidate id=${device.id} name=${device.name}")

            transport.connect(device.id, timeoutMs = 12_000)
            Log.i(TAG, "gatt_ready=true id=${device.id}")

            val reassembler = F32cFragmentReassembler()
            val scanResult = CompletableDeferred<F32cScanResult>()
            val startedAt = SystemClock.elapsedRealtime()
            collector = launch {
                transport.notifications.collect { notification ->
                    // Elapsed-realtime keeps the reassembler's timeout monotonic across
                    // wall-clock steps while the device is under test.
                    val complete = reassembler.accept(notification.payload, SystemClock.elapsedRealtime() - startedAt)
                    if (complete != null) {
                        payloads += complete.toString(StandardCharsets.UTF_8)
                        Log.i(TAG, "notification payload=${payloads.last()}")
                        F32cResponses.scanResult(payloads.last())?.let { scan ->
                            if (scanResult.isActive) scanResult.complete(scan)
                        }
                        F32cResponses.queryResult(payloads.last())?.let { queryResults += it }
                    }
                }
            }
            // Give the collector a moment to subscribe before the first write so an
            // early device answer cannot slip past the replay-zero notification bus.
            delay(250)

            // Read-only bus discovery: the firmware scans motor addresses and answers one
            // scan_result listing them. Motor addresses are discovered here, not hardcoded.
            // scan does not enable, move, set speed, or alter calibration.
            val scanReceipt = withTimeout(WRITE_TIMEOUT_MS) { transport.writeCommand("{\"cmd\":\"scan\"}") }
            Log.i(TAG, "scan_transport_accepted=${scanReceipt.transportAccepted}")
            val discovered = withTimeoutOrNull(10_000) { scanResult.await() }
            assertTrue("scan_result did not arrive within 10s; payloads=$payloads", discovered != null)
            Log.i(TAG, "scan_result ok=${discovered!!.ok} motors=${discovered.motors}")
            assertTrue("bus scan reported ok=false; payloads=$payloads", discovered.ok)
            assertFalse("scan_result listed no motors; payloads=$payloads", discovered.motors.isEmpty())

            // Read-only telemetry per discovered address. These do not enable, move,
            // set speed, or alter calibration either.
            for (motor in discovered.motors) {
                val receipt = withTimeout(WRITE_TIMEOUT_MS) {
                    transport.writeCommand("{\"cmd\":\"query\",\"addr\":${motor.id},\"type\":\"voltage\"}")
                }
                Log.i(TAG, "query_voltage_addr=${motor.id} transport_accepted=${receipt.transportAccepted}")
                delay(1_500)
            }
            delay(1_200)
            for (motor in discovered.motors) {
                val answered = queryResults.any { it.addr == motor.id && it.type == "voltage" }
                Log.i(TAG, "query_result_received_addr=${motor.id} answered=$answered")
            }
        } finally {
            collector?.cancel()
            runCatching { transport.disconnect() }
            transport.close()
        }
        assertTrue(
            "no query_result event was decoded (answers may have failed on the device bus); payloads=$payloads",
            queryResults.isNotEmpty(),
        )
        }
    }

    private companion object {
        const val TAG = "F32C_READONLY"
        const val WRITE_TIMEOUT_MS = 8_000L
    }
}
