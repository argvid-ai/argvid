# Tests

## Host verification commands

From the repository root:

- `python -m py_compile src/tools/f32c_protocol.py src/tools/test_cli.py src/tools/web_app.py` - compiles all three Python modules; exit status 0 with no output means success. Requires Python 3.12+.
- From `src/app`, after `flutter pub get`: `flutter test` - runs the app unit tests in `src/app/test/widget_test.dart` (pure logic: angle normalization, command throttling behavior, log formatting). Requires the Flutter SDK; expected result is all tests passing.

## Evidence status

- Host Python compile check: pending first CI run on this branch.
- flutter test: passing on the authoring host (Flutter 3.x stable, Windows); to be reproduced in CI.
- Real-device firmware upload, BLE end-to-end integration, and hardware-in-the-loop motion/safety tests: pending, never passed. Host checks do not certify hardware safety.
