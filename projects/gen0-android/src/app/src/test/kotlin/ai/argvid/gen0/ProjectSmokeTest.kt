package ai.argvid.gen0

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSmokeTest {
    @Test
    fun localCameraUsesAnIsolatedApplicationSandbox() {
        assertEquals("ai.argvid.gen0.camera", BuildConfig.APPLICATION_ID)
    }
}
