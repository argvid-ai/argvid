# Tests

## Host verification commands

All paths relative to `projects/gen0-gimbal/`. Python 3.12+ required for the tools check.

- `python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py` — compiles the three Python tool modules; exit status 0 with no output is success.
- `python -m unittest discover -s tests -v` — runs the Python unit tests below (default pattern `test*.py`; requires `pip install -r src/tools/requirements.txt`).
- `arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc src/firmware/esp32-firmware` — compiles the ESP32-S3 firmware against the pinned toolchain (arduino-cli 1.5.1, esp32 core 3.3.11, ArduinoJson 7.4.3); exit 0 is success. First run needs the core and library installed (see project README).

App unit tests (from `projects/gen0-gimbal/src/app`, requires the Flutter stable SDK):

- `flutter pub get` then `flutter test` — runs the suites below; all tests must pass.

## What the tests actually cover

Python unit tests (`tests/test_f32c_address_check.py`, `tests/test_web_security.py`):

- Review fix P1-4: feedback frames with a mismatched address are rejected (`valid=false`) in the Python reference protocol, with the success path, short-frame, and bad-BCC paths as controls.
- Review fix P1-6: concurrent `_send_axis` transactions are atomic — a deterministic two-thread interleaving test asserts no `set_addr` from one axis can execute between another axis's `set_addr` and its command (the wrong-axis reproduction from the review fails without the lock).
- Review fix P1-7: every `/api/*` call requires the per-launch token (query or `X-Auth-Token` header); wrong/missing tokens get 401 including state-changing endpoints; the rendered page carries the injected token; the default bind address is loopback-only.

App unit tests (`src/app/test/`):

- `test/widget_test.dart` — model and utility behavior: `Motor`/`GimbalInfo`/`WifiInfo` fields and `copyWith`, `MotorParams` decoding (`-1`/null → unset) and summary, hex log formatting, direction colors.
- `test/gimbal_controller_test.dart` — app control behavior (multi-turn position mode): keypad position stepping (`move`, no command on release), step accumulation and clamping, relative joystick dragging (center-recall + 130 ms throttling + final-position send on release), single-axis levers with clamping, one-decimal rounding, center/origin command and state reset, and the motion-speed sequence (set_mode/enable/set_speed per axis + move, skipped for unconfigured axes).

Not covered by these tests (no claim made): BLE transport (scan/connect/notify dispatch), firmware execution on hardware (including the P1-1 disconnect stop, P1-2 stop bypass, P1-3 firmware-side limit enforcement, and P1-5 mode-cache behavior in C++, which would need host-compilable firmware logic harnesses or hardware-in-the-loop runs), and physical gimbal motion. Those remain pending; the C++ side is currently verified by compilation with the pinned toolchain plus the documented device acceptance checklist.

## Evidence status

- Python compile check, `flutter test` (24 tests), and the Python unit tests (13 tests): passing on the authoring host (Python 3.12.12, Flutter stable, Windows) and run in CI on every pull request via `.github/workflows/gimbal.yml`.
- Firmware compile: verified on the authoring host with the pinned toolchain (arduino-cli 1.5.1, esp32 core 3.3.11, ArduinoJson 7.4.3, FQBN `esp32:esp32:esp32s3:CDCOnBoot=cdc`) — 1,144,705 bytes flash (87%), 50,484 bytes RAM (15%); also compiled in CI. Not flashed to hardware in this delivery.
- Real-device firmware flash, BLE end-to-end integration, and hardware-in-the-loop motion/safety tests (including the disconnect fail-stop mechanism and jog position limiting): pending, never passed. Host checks do not certify hardware safety.
