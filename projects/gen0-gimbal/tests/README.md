# Tests

## Host verification commands

All paths relative to `projects/gen0-gimbal/`. Python 3.12+ required for the tools check.

- `python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py` — compiles the three Python tool modules; exit status 0 with no output is success.
- `python -m unittest discover -s tests -v` — runs the Python unit tests below (default pattern `test*.py`; requires `pip install -r src/tools/requirements.txt`).
- `arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc src/firmware/esp32-firmware` — compiles the ESP32-S3 firmware against the pinned toolchain (arduino-cli 1.5.1, esp32 core 3.3.11, ArduinoJson 7.4.3); exit 0 is success. First run needs the core and library installed (see project README).

App unit tests (from `projects/gen0-gimbal/src/app`, requires the Flutter stable SDK):

- `flutter pub get` then `flutter test` — runs the suites below; all tests must pass.

## What the tests actually cover

Python unit tests (`tests/test_f32c_address_check.py`, `tests/test_web_security.py`, `tests/test_result_semantics.py`):

- Review fix P1-4: feedback frames with a mismatched address are rejected (`valid=false`) in the Python reference protocol, with the success path, short-frame, and bad-BCC paths as controls.
- Review fix P1-6: concurrent `_send_axis` transactions are atomic — a deterministic two-thread interleaving test asserts no `set_addr` from one axis can execute between another axis's `set_addr` and its command (the wrong-axis reproduction from the review fails without the lock).
- Review fix P1-7: every `/api/*` call requires the per-launch token (query or `X-Auth-Token` header); wrong/missing tokens get 401 including state-changing endpoints; the rendered page carries the injected token; the default bind address is loopback-only.
- Result semantics: write-only commands report transport success without device confirmation; short serial writes fail; valid address-matched feedback sets `device_confirmed=true`.

App unit tests (`src/app/test/`):

- `test/widget_test.dart` — model and utility behavior: `Motor`/`GimbalInfo`/`WifiInfo` fields and `copyWith`, `MotorParams` decoding (`-1`/null → unset) and summary, hex log formatting, direction colors.
- `test/ble_json_receiver_test.dart` — 13 local receiver regressions: empty initial value, legacy JSON, 40-line scan logs at MTU 23/247, split UTF-8, invalid/truncated/missing/repeated fragments, recovery, timeout, disconnect reset, separate FF02/FF04 assembly and size bounds. This exercises the production Dart receiver with wire-format fixtures; it does not simulate the BLE radio or execute the C++ sender.
- `test/gimbal_controller_test.dart` — app control behavior (multi-turn position mode): keypad position stepping (`move`, no command on release), step accumulation and clamping, relative joystick dragging (center-recall + 130 ms throttling + final-position send on release), single-axis levers with clamping, one-decimal rounding, center/origin command and state reset, and the motion-speed sequence (set_mode/enable/set_speed per axis + move, skipped for unconfigured axes).

Not covered by these tests (no claim made): BLE transport (scan/connect/notify dispatch), firmware execution on hardware (including the P1-1 disconnect stop, P1-2 stop bypass, P1-3 firmware-side limit enforcement, and P1-5 mode-cache behavior in C++, which would need host-compilable firmware logic harnesses or hardware-in-the-loop runs), and physical gimbal motion. Those remain pending; the C++ side is currently verified by compilation with the pinned toolchain plus the documented device acceptance checklist.

## Evidence status

- Python compile check, `flutter test` (47 tests), and the Python unit tests (19 tests, including test_f1_f2_f3_review.py): passing on the authoring host and in CI. CI coverage applies to the public PR #8 revision.
- Firmware compile: verified on the authoring host and in CI with the pinned toolchain (arduino-cli 1.5.1, esp32 core 3.3.11, ArduinoJson 7.4.3, FQBN `esp32:esp32:esp32s3:CDCOnBoot=cdc`). The 2004 firmware (SHA-256 `2684BD7E…4D56B8`) has been flashed and field-verified on the authoring rig (dual-axis motion, parameter auto-restore after motor power cycle, nearest-equivalent centering).
- Hardware-in-the-loop motion/safety certification: not performed or claimed. Field verification on the authoring rig covers the listed scenarios but does not constitute HIL. Host firmware regressions (tests/firmware_host/) cover nearest-equivalent centering, motor-only reboot recovery, tilt out-of-range rejection, stale-telemetry rejection, F1 enable/mode-failure zero-speed + cache re-prepare, F2 abortMotion preemption, and serial-write-hook intra-axis abort regressions (hook injects abort at mode/enable/speed/target); compilation requires a system C++ compiler — not available on the authoring Windows host, verified by CI on Ubuntu where applicable).
