# Tests

## Host verification commands

All paths relative to `projects/gen0-gimbal/`. Python 3.12+ required for the tools check.

- `python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py` — compiles the three Python tool modules; exit status 0 with no output is success.
- `pio run -d src/firmware` — compiles the ESP32-S3 firmware against the pinned PlatformIO toolchain (espressif32 7.0.1 / Arduino core 2.0.17, ArduinoJson 7.x); exit 0 is success. Requires PlatformIO (`pip install platformio`).

App unit tests (from `projects/gen0-gimbal/src/app`, requires the Flutter stable SDK):

- `flutter pub get` then `flutter test` — runs the suites below; all tests must pass.

## What the app tests actually cover

- `test/widget_test.dart` — model and utility behavior: `Motor`/`GimbalInfo`/`WifiInfo` fields and `copyWith`, hex log formatting, direction colors.
- `test/gimbal_controller_test.dart` — app control behavior: jog command emission (axis/dir/speed), 130 ms joystick move throttling, final-position send on release, pan ±180°/tilt ±90° clamping before transport, one-decimal angle rounding, center/zero command and state reset.

Not covered by these tests (no claim made): BLE transport (scan/connect/notify dispatch), firmware execution on hardware, and physical gimbal motion. Those require device and hardware-in-the-loop runs.

## Evidence status

- Python compile check and `flutter test` (17 tests): passing on the authoring host (Python 3.12.12, Flutter stable, Windows) and run in CI on every pull request via `.github/workflows/gimbal.yml`.
- Firmware `pio run` compile: toolchain pinned for reproducibility; verified in CI. Not flashed to hardware in this delivery.
- Real-device firmware flash, BLE end-to-end integration, and hardware-in-the-loop motion/safety tests: pending, never passed. Host checks do not certify hardware safety.
