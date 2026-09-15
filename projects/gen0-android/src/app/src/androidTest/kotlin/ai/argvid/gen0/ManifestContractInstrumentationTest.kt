package ai.argvid.gen0

import android.Manifest
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the installed-app contract check independent from Compose ActivityRule startup.
 * This isolates runner/manifest failures from Activity lifecycle failures on physical devices.
 */
class ManifestContractInstrumentationTest {
    @Test
    fun targetManifestCanBeReadWithoutLaunchingActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().filter {
            it == Manifest.permission.INTERNET ||
                it == Manifest.permission.ACCESS_NETWORK_STATE ||
                it == Manifest.permission.ACCESS_FINE_LOCATION ||
                it == Manifest.permission.BLUETOOTH ||
                it == Manifest.permission.BLUETOOTH_SCAN ||
                it == Manifest.permission.BLUETOOTH_CONNECT ||
                it == Manifest.permission.RECORD_AUDIO ||
                it == Manifest.permission.CAMERA
        }

        // API 31+ drops maxSdkVersion=30 permissions (ACCESS_FINE_LOCATION, legacy
        // BLUETOOTH) at package parse time, so they are expected only below API 31.
        val expected = mutableSetOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT < 31) {
            expected += Manifest.permission.ACCESS_FINE_LOCATION
            expected += Manifest.permission.BLUETOOTH
        }
        assertEquals(expected, requested.toSet())
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertTrue(
            packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                0,
            ).exported,
        )
    }
}
