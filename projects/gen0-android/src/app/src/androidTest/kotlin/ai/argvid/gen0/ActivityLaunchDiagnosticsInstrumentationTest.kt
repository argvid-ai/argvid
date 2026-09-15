package ai.argvid.gen0

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded diagnostics for devices where instrumentation-driven Activity startup
 * (Compose ActivityRule / ActivityScenario / startActivitySync) never completes.
 *
 * Evidence from the 2026-09-13 Vivo run: this OEM silently drops background
 * activity starts issued from the instrumentation context, and the framework's
 * waitForMonitorWithTimeout then never returns. This class therefore never uses
 * monitor waits or runOnMainSync: each probe launches, then polls the system
 * server's `dumpsys` state directly on a deadline, so every path terminates and
 * failure leaves evidence. There is deliberately no cleanup step: the test runs
 * inside the target process, so force-stopping or finishing the activity from
 * here would kill the instrumentation itself. Product Activity code is not
 * modified; this class only observes startup paths.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLaunchDiagnosticsInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetPackage = instrumentation.targetContext.packageName

    @Test
    fun shellStartedActivityReachesResumedWithinTimeout() {
        val component = ComponentName(instrumentation.targetContext, MainActivity::class.java)
        val resumed = launchAndAwaitResumed { shellOutput("am start -n ${component.flattenToString()}") }
        assertTrue("shell `am start` did not reach RESUMED within ${AWAIT_TIMEOUT_MS}ms", resumed)
    }

    @Test
    fun targetContextStartActivityReachesResumedWithinTimeout() {
        val resumed = launchAndAwaitResumed {
            val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.targetContext.startActivity(intent)
        }
        assertTrue(
            "target-context startActivity did not reach RESUMED within ${AWAIT_TIMEOUT_MS}ms",
            resumed,
        )
    }

    /**
     * Runs [launch], then polls `dumpsys` (system-server view, immune to a wedged
     * app main thread) until this package's MainActivity is the resumed/top activity.
     * Success requires BOTH the focused window and the top-resumed record to name
     * this package, so stale post-kill records alone cannot pass.
     */
    private fun launchAndAwaitResumed(launch: () -> Unit): Boolean {
        launch()
        Log.i(TAG, "launch_issued; polling dumpsys for resumed $targetPackage")
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        var evidence = ""
        while (System.currentTimeMillis() < deadline) {
            evidence = windowFocusEvidence()
            if (isResumed(evidence)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Log.i(TAG, "launch_issued; NOT resumed. Evidence:\n$evidence")
        return false
    }

    private fun isResumed(evidence: String): Boolean {
        val focused = evidence.lineSequence().any { it.contains("mCurrentFocus") && it.contains("$targetPackage/") }
        val resumed = evidence.lineSequence().any {
            it.contains("ResumedActivity") && it.contains("$targetPackage/")
        }
        return focused && resumed
    }

    private fun windowFocusEvidence(): String =
        shellOutput("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'") + "\n" +
            shellOutput("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")

    private fun shellOutput(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
        descriptor.close()
        return "$ $command\n$output".trim()
    }

    private companion object {
        const val TAG = "ACTIVITY_LAUNCH_DIAG"
        const val AWAIT_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
