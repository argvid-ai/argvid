package ai.argvid.gen0.capture

import android.Manifest
import androidx.camera.core.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraXSamplerLifecycleTest {
    @Test
    fun repeatedStartIsRejectedAndStopReleasesBinding() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.CAMERA,
        )
        val owner = withContext(Dispatchers.Main) {
            TestLifecycleOwner(Lifecycle.State.RESUMED)
        }
        val sampler = CameraXSampler(
            context = context,
            configuration = ProxyConfiguration(width = 960, height = 540, targetFps = 8),
            processor = FrameProcessor { _, _, _, _ ->
                FrameProcessResult.Rejected(FrameRejected.InvalidSource)
            },
        )
        val noSurface = Preview.SurfaceProvider { request -> request.willNotProvideSurface() }

        try {
            sampler.start(owner, noSurface, emptyList())
            assertEquals(CameraSamplerState.Running, sampler.state.value)

            val repeated = try {
                sampler.start(owner, noSurface, emptyList())
                throw AssertionError("Expected CameraSamplerException")
            } catch (error: CameraSamplerException) {
                error
            }
            assertEquals(CameraSamplerFailure.AlreadyRunning, repeated.reason)
        } finally {
            sampler.stop()
        }

        assertEquals(CameraSamplerState.Stopped, sampler.state.value)
    }
}
