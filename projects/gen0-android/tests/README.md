# Tests

Run the exact commands in [README](../README.md#verification). Module unit tests cover capture gating/stopping, coordinate/mask processing with explicit synthetic rectangles, rescue buffering, session state, deterministic gimbal simulation, MediaStore save sequencing, Today verification/playback state and local deletion retries/idempotency. `testing/fixtures` exercises local synthetic state-transition envelopes, not shared-protocol conformance.

`test_storage_schema.py` executes exported Room SQL in host SQLite and verifies a fresh two-table local catalog and foreign-key enforcement. It complements, not substitutes for, Android Room tests. `check_proxy_fixtures.swift` decodes all 16 generated JPEGs and checks dimensions, distinctness and eight actual color bars.

`test_apk_policy.py` uses SDK Build-Tools36.0.0 `aapt` to inspect the built APK's application identity, complete requested-permission set, and packaged backup/device-transfer exclusions. This checks the emitted Android policy payload, not merely source strings; OEM backup behavior remains untested.

Android tests cover CameraX lifecycle, codec output, MediaStore integration, Session/Today UI and launcher/local-permission behavior. Their compilation and execution status are separate in [verification](../docs/verification.md). No physical-device, BLE, face-detector, performance or HIL result is implied by unit-test success.

Restart tests use real capture/moment controllers through SessionViewModel, including background/STOP, explicit restart, fresh coverage and another rescue. Other regressions cover permission/simulator notices versus save results, pending start cancellation, queued Hold deadlines, and an actual temporary staged file through save-cleanup failure and Today deletion receipt/retry.

`SessionRestartBindingTest` connects the real controllers to a stateful host sampler: a completed stop followed by a fresh binding and an old blocked encode must still tear down that new binding when Today/background stops restart. Completing the old encode cannot restart it. A second regression cancels a partial host binding and checks teardown before cancellation completes.

`test_lifecycle_ownership.py` is a host source-wiring guard, not an Android lifecycle execution test. `RuntimeRecreationTest`, `MomentPlayerLifecycleTest`, and the real Room staged-file integration in `LocalDatabaseTest` are compiled instrumentation whose execution remains pending. The player test checks attachment after background/foreground and a second play; it does not claim decoded-frame or codec success from its synthetic URI.
