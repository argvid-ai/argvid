package ai.argvid.gen0

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ReleaseJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchAndNavigateToTruthfulTodaySurface() {
        compose.onNodeWithText("Session").assertExists()
        compose.onNodeWithText("启用语音 STOP").assertDoesNotExist()
        compose.onNodeWithText("隐私遮罩预览").assertDoesNotExist()

        compose.onNodeWithText("Today").performClick()

        compose.onNodeWithContentDescription("Today 页面").assertExists()
    }

    @Test
    fun installedAppRequiresOnlyCameraAndDoesNotBackUpMediaMetadata() {
        val context = compose.activity
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val dangerousOrNetwork = info.requestedPermissions.orEmpty().filter {
            it == Manifest.permission.INTERNET ||
                it == Manifest.permission.ACCESS_NETWORK_STATE ||
                it == Manifest.permission.BLUETOOTH_SCAN ||
                it == Manifest.permission.BLUETOOTH_CONNECT ||
                it == Manifest.permission.RECORD_AUDIO ||
                it == Manifest.permission.CAMERA
        }
        assertEquals(listOf(Manifest.permission.CAMERA), dangerousOrNetwork)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
