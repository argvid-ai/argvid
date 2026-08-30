# Verification record

Verified 2026-08-30 on macOS with JDK17, Android SDK36/Build-Tools36.0.0 and Gradle9.3.1. This is host/build evidence only; the device matrix for the delivered revision remains pending.

An Ubuntu CI attempt on 2026-08-30 reached the root-project classpath and reported four missing strict-verification records: Guava parent 33.3.1-jre POM, JUnit BOM 5.10.2/5.11.0-M2 module metadata, and Kotlin coroutines BOM 1.8.0 POM. A separate macOS cold-cache run then reached `:app:debugRuntimeClasspath` and reported the Guava parent 33.3.1-android POM, followed by the coroutines BOM 1.10.2 POM in `:data:media:detachedConfiguration5` and the Guava parent 33.2.1-jre POM in `:data:media:kspDebugKotlinProcessorClasspath`. The generated verification metadata now records the seven reviewed SHA-256 values without changing strict mode. The Guava POMs are checked against Maven Central's published SHA-1 because their `.sha256` sidecars were unavailable; the JUnit modules and coroutines POM SHA-256 values match Maven Central sidecars. The metadata also records both the macOS AAPT2 artifact and the Linux artifact `aapt2-9.1.1-14792394-linux.jar`, whose SHA-256 is `e7ae17af6e4093c771243e82d66462353de87befaac206bfb43e557ac1c34440` from Google's published checksum. Consult the [Android workflow](https://github.com/argvid-ai/argvid/actions/workflows/android.yml) and its result for the exact commit; a macOS cold-cache result does not establish Linux dependency completeness or an Ubuntu result.

## Executed checks

The [README commands](../README.md#verification) are the normal entrypoints. The final full run added `--rerun-tasks --no-build-cache` to force fresh execution, with dedicated Gradle storage outside the source tree:

```bash
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict testDebugUnitTest :core:domain:test :testing:fixtures:test lintDebug :app:assembleDebug assembleDebugAndroidTest --rerun-tasks --no-build-cache
```

Result: `BUILD SUCCESSFUL in 1m 6s`; `480 actionable tasks: 480 executed`. The JUnit XML results contain **99 tests, zero failures, zero errors, zero skipped**. Android instrumentation APKs compiled; none were installed or executed.

| Unit-test module | Tests |
|---|---:|
| app | 1 |
| core/domain | 33 |
| adapter/capture | 12 |
| adapter/gimbal | 7 |
| data/media | 20 |
| feature/session | 17 |
| feature/today | 6 |
| testing/fixtures | 3 |

`python3 tests/test_storage_schema.py -v`: two passed. `python3 tests/test_apk_policy.py -v` with `ANDROID_HOME` configured: two passed. The latter inspected the generated APK identity, complete permission set, backup flag and packaged data-extraction exclusions. The app has CAMERA, normal WAKE_LOCK and its signature-scoped receiver permission, but no INTERNET, network-state, Bluetooth or microphone permission.

`python3 tests/test_lifecycle_ownership.py -v`: two source-wiring guards passed. They check retained runtime ownership and reactive player-surface wiring; they are not Android lifecycle execution evidence.

Synthetic fixture regeneration completed. `swift tests/check_proxy_fixtures.swift src/data/media/src/androidTest/assets/proxy-frames`: `PASS: 16 distinct 960x540 JPEGs, each with eight decoded color bars`. The test compares decoded RGB samples, avoiding display-profile color conversion. The generated digit/color-bar content was also visually inspected.

The selected-file secret-pattern command in README and `git diff --check` exited zero. The pattern scan is limited and does not grant publication approval. No build cache, SDK installation, log, device ID, APK or private source history is part of the tracked package.

## Warnings retained

Lint completed with zero errors, but is not warning-free. The final reports contain: app 16 warnings (old target API, newer Gradle/AGP/library versions and redundant resource API qualifier), adapter/capture 1 `UseKtx` warning, feature/today 2 warnings (`ModifierParameter`, `UseKtx`), and zero warnings in adapter/gimbal, data/media and feature/session. Newer-version advice can change over time. No dependency or SDK upgrade was performed to silence it.

The build reports an SDK XML-version mismatch (reader supports version3, encountered version4) and packages certain native AndroidX camera/graphics libraries without stripping debug symbols. These warnings do not represent device validation. Build dependencies and checksums are recorded in [THIRD_PARTY](../THIRD_PARTY.md).

## Regression scope

Test-first failures demonstrated that the simulator must not request Bluetooth, the application must use a distinct sandbox, the fresh database must contain only local capture storage, exported filenames must use the local-camera namespace, generated color bars must actually decode to colors, and the packaged app must exclude private data from device transfer. Those checks are green for this package. Retained local save, deletion retry/idempotency, capture, session and playback tests remain meaningful; removed network/policy features have no test-count claim here.

Room storage SQL is exercised on host SQLite. The new Android Room DAO lifecycle test and launcher/permission tests are compiled only. A passing host schema or packaged-manifest test does not certify Android backup behavior. The backup configuration follows [Android's backup and transfer controls](https://developer.android.com/identity/data/autobackup); gallery apps may independently handle saved MediaStore clips.

Additional failing-then-passing host regressions cover real capture/moment controllers through SessionViewModel for stop → confirmed restart → new coverage → rescue; permission/simulator notices followed by save progress/success/failure; cancellation of a pending start on background; and Hold/Manual superseding old simulator deadlines. Domain tests also show that restart waits for older encode/save work and retains unresolved cleanup.

Two additional failing-then-passing tests use real Session/capture/moment orchestration with a stateful sampler at the external camera boundary. They cover completed stop → fresh binding → restart waiting for old encode → Today/background stop, verify the newest binding is torn down, and verify old encode completion cannot restart it. Cancellation during a partial host binding also tears it down before completing. The actual SessionRuntime uses the same controller binding boundary; these are host regressions, not device CameraX execution.

The staged-file integration test uses an actual temporary file, the production record-to-Room-entity mapping, and LocalDeletionCoordinator/AppPrivateStagingStore. Save/discard failure leaves a linked cleanup-pending row; a failed Today staging deletion stays retryable without a completed receipt; retry removes the file before completion. The DAO adapter itself is exercised by the compiled-only Room integration test. Today exposes the persisted cleanup link after repository recreation.

`RuntimeRecreationTest` and `MomentPlayerLifecycleTest` compile checks for retained owners across Activity recreation and a surviving PlayerView attaching the next player after background/foreground/play. Their runtime assertions have **not** been executed. The surface test's synthetic URI checks player identity/attachment, not decoded video correctness. No runtime recreation, frame-rendering, or device-behavior pass is inferred from compilation.

## Wrapper verification

The wrapper JAR is unchanged and its SHA-256 is:

```text
b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13
```

This matches [Gradle's 9.3.1 wrapper checksum](https://services.gradle.org/distributions/gradle-9.3.1-wrapper.jar.sha256), checked 2026-08-30. Recheck locally with `shasum -a 256 src/gradle/wrapper/gradle-wrapper.jar` (or `sha256sum` on Linux). `src/gradle/wrapper/gradle-wrapper.properties` pins distribution SHA-256 `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`. Wrapper/distribution license notices are retained; dependencies are verified using the separate generated metadata.

## Pending device matrix and exclusions

| Check | Delivered-revision status |
|---|---|
| API29+ physical device/emulator camera capture → rescue → MediaStore → Today → delete | pending; not run |
| Android instrumentation execution (CameraX, Room, codec, MediaStore, Compose, launcher) | pending; compiled only |
| Lifecycle/process-death/resume and storage-permission/OEM variation | pending; no new device result |
| Native H.264 encoding correctness and proxy quality/performance/thermal behavior | pending on device |
| Backup/restore/device-transfer behavior on manufacturer implementations | pending; packaged policy checked only |
| Real BLE, gimbal firmware/watchdog/stop, HIL | pending; no hardware implementation/validation |
| Face detector, automatic privacy masking, single-photo capture, audio | not implemented; no validation claim |
| Signed release / store delivery / safety certification | not performed or claimed |

Before running instrumentation, obtain device-operation authority and configure an appropriate API29+ target. From `src`, `./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest` is the execution entrypoint. Root protocol/conformance and public placement checks belong to repository integration; passing this Android build is not canonical L2 conformance or upstream acceptance.
