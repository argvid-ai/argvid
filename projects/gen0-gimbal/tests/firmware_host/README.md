# Firmware host regression tests

These tests compile the real `f32c_protocol.cpp` and `gimbal_controller.cpp`
against a small Arduino/serial stub. They exercise protocol frames rather than
checking implementation text:

* a motor-only reboot leaves the ESP32 cache unaware of the reset, so every
  explicit position move restores mode, enable, and position speed before each target;
* a pan total angle of 730° centers at the nearest equivalent 720° target;
* a 227° tilt telemetry reading is rejected and causes zero-speed safety stop
  frames without emitting a position target.
* missing or wrong-type total-angle telemetry is rejected before any position
  target is sent.

From PowerShell in this directory, with the Android NDK clang++ available:

```powershell
$clang = 'D:\Andriod\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$ndk = 'D:\Andriod\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64'
$root = Resolve-Path '..\..\src\firmware\esp32-firmware'
& $clang '--target=aarch64-linux-android24' ('--sysroot=' + (Join-Path $ndk 'sysroot')) `
  -stdlib=libc++ -static-libstdc++ -std=c++17 -I stubs -I $root `
  gimbal_controller_regression.cpp `
  (Join-Path $root 'gimbal_controller.cpp') `
  (Join-Path $root 'f32c_protocol.cpp') `
  -o firmware_host_regression
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

The output is an Android arm64 executable despite the `.exe`-free target; on a
developer workstation with an authorized USB device, it can be run without
starting the app or touching BLE/motor hardware:

```powershell
$adb = 'D:\Andriod\Sdk\platform-tools\adb.exe'
& $adb push .\firmware_host_regression /data/local/tmp/gimbal_firmware_host_test
& $adb shell chmod 755 /data/local/tmp/gimbal_firmware_host_test
& $adb shell /data/local/tmp/gimbal_firmware_host_test
& $adb shell rm /data/local/tmp/gimbal_firmware_host_test
```
