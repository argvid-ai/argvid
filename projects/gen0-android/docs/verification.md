# Verification record

Verified 2026-08-30 on macOS with JDK17, Android SDK36/Build-Tools36.0.0 and Gradle9.3.1. A Windows host run on 2026-09-04 used JDK17 `17.0.20.1`, Android SDK36/Build-Tools36.0.0 and the same Gradle9.3.1 wrapper. This is host/build evidence only; the device matrix for the delivered revision remains pending.

Finalization rerun on 2026-09-09 used the same Windows JDK17/Android SDK36 toolchain. The README Gradle command completed successfully in 33 seconds (`480 actionable tasks: 8 executed, 5 from cache, 467 up-to-date`); the current reports contain 135 host tests with zero failures or errors, and both debug APKs were rebuilt. The storage-schema, lifecycle-ownership and APK-policy checks each passed two tests, and `git diff --check` passed. The repository's Bash secret-scan entrypoint could not produce a valid result on this Windows host because `bash` resolves to the system Linux launcher; an equivalent selected-text scan using the script's three patterns found no matches. The optional Swift fixture check was not run because Swift/AppKit is unavailable on Windows. Instrumentation was compiled but not installed or executed.

The Windows run executed the strict README Gradle command and completed successfully in 14m 21s (`480 actionable tasks: 405 executed, 75 from cache`). Unit tests, lint, the debug APK and the debug instrumentation APK all built. The local Windows AAPT2 jar SHA-256 `e27e3c895bfb9698c956d5e35c514570a0f084874c081d95a690181a606dbe10` matches Google's published `.sha256`; the corresponding entry is recorded in `src/gradle/verification-metadata.xml`. Instrumentation was compiled but not installed or executed.

A follow-up Windows run after adding Kotlin's `@ConsistentCopyVisibility` annotation to the private-constructor `DetectionSensitivity` data class completed the same strict command in 2m 29s (`480 actionable tasks: 92 executed, 388 up-to-date`). The previous data-class copy-visibility warning no longer appears; the annotation preserves the constructor boundary.

The Android Studio source-model failure reported on 2026-09-05 was caused by 108 unique source-classifier artifacts that were not yet listed in verification metadata (34 from Google Maven and 74 from Maven Central, repeated across the tooling configurations). Their SHA-256 values were computed from the exact files in the local Gradle cache, all 108 archives opened successfully, and a temporary Gradle 9.3.1 project resolved all 108 under `--dependency-verification=strict`. The metadata now contains 114 source entries in total (the prior six plus these 108). The new entries retain the generated-metadata origin; no independent remote sidecar or signature check was performed for this batch. The full project strict check was rerun successfully (`480 actionable tasks: 6 executed, 474 up-to-date`). Android Studio's native window was not exposed in this Codex session, so an IDE UI Sync itself remains unobserved.

A subsequent Android Studio model attempt on 2026-09-05 requested five additional unqualified source classifiers: the Kotlin Compose compiler plugin, Kotlin FUS statistics plugin, Kotlin Gradle plugin, Kotlin Gradle plugin API, and Guava 33.3.1-jre. These are distinct artifact names from the already recorded `-gradle813` variants. SHA-256 values were computed from the exact newly cached files, all five archives opened successfully, and `prepareKotlinBuildScriptModel` plus the full project strict check completed successfully. The metadata now contains 119 source entries; this additional batch also retains the generated-metadata origin and has not received an independent remote sidecar or signature check.

A further Android Studio model attempt on 2026-09-05 requested three KSP plain source classifiers: `symbol-processing-api`, `symbol-processing-common-deps`, and `symbol-processing-gradle-plugin`, all at version `2.3.9`. Their SHA-256 values were computed from the exact cached files, all three archives opened successfully, and `prepareKotlinBuildScriptModel` plus the full project strict check completed successfully. The metadata now contains 122 source entries; this batch retains the generated-metadata origin and has not received an independent remote sidecar or signature check.

The repeated source/javadoc failures had a tooling-specific cause rather than an expanding runtime dependency set. Google-vendor Android Studio's additional-classifier model resolves `sources` and `javadoc` variants through detached configurations for each module. To make that model import stable, `src/gradle/verification-metadata.xml` now uses Gradle's [documented IDE workaround](https://docs.gradle.org/current/userguide/dependency_verification.html#sec:skipping_javadocs_and_sources): only filenames matching `.*-sources[.]jar` and `.*-javadoc[.]jar` are listed under `<trusted-artifacts>`. The existing exact source hashes remain as provenance, but matching classifier archives are intentionally not hash-verified; every other artifact (including POM/module metadata, runtime/build JARs, AARs and the wrapper) remains under strict verification. This is a deliberate security exception for IDE metadata import and requires human review before public release. With the rule in place, strict `prepareKotlinBuildScriptModel` completed in 16 seconds and the full 480-task Windows regression completed successfully on 2026-09-05.

An Ubuntu CI attempt on 2026-08-30 reached the root-project classpath and reported four missing strict-verification records: Guava parent 33.3.1-jre POM, JUnit BOM 5.10.2/5.11.0-M2 module metadata, and Kotlin coroutines BOM 1.8.0 POM. A separate macOS cold-cache run then reached `:app:debugRuntimeClasspath` and reported the Guava parent 33.3.1-android POM, followed by the coroutines BOM 1.10.2 POM in `:data:media:detachedConfiguration5` and the Guava parent 33.2.1-jre POM in `:data:media:kspDebugKotlinProcessorClasspath`. The generated verification metadata now records the seven reviewed SHA-256 values without changing strict mode. The Guava POMs are checked against Maven Central's published SHA-1 because their `.sha256` sidecars were unavailable; the JUnit modules and coroutines POM SHA-256 values match Maven Central sidecars. The metadata records the macOS AAPT2 artifact, the Linux artifact `aapt2-9.1.1-14792394-linux.jar` with SHA-256 `e7ae17af6e4093c771243e82d66462353de87befaac206bfb43e557ac1c34440`, and the Windows artifact `aapt2-9.1.1-14792394-windows.jar` with SHA-256 `e27e3c895bfb9698c956d5e35c514570a0f084874c081d95a690181a606dbe10`; both platform values came from Google's published checksums. Consult the [Android workflow](https://github.com/argvid-ai/argvid/actions/workflows/android.yml) and its result for the exact commit; the local Windows/macOS host runs do not establish Linux dependency completeness or device behavior.

## Executed checks

The [README commands](../README.md#verification) are the normal entrypoints. The final full run added `--rerun-tasks --no-build-cache` to force fresh execution, with dedicated Gradle storage outside the source tree:

```bash
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict testDebugUnitTest :core:domain:test :testing:fixtures:test lintDebug :app:assembleDebug assembleDebugAndroidTest --rerun-tasks --no-build-cache
```

Result: `BUILD SUCCESSFUL in 58s`; `480 actionable tasks: 480 executed`. The 24 fresh JUnit XML reports contain **104 tests, zero failures, zero errors, zero skipped**. Android instrumentation APKs compiled; none were installed or executed.

| Unit-test module | Tests |
|---|---:|
| app | 1 |
| core/domain | 37 |
| adapter/capture | 12 |
| adapter/gimbal | 7 |
| data/media | 20 |
| feature/session | 18 |
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

On 2026-09-09, a test-first regression on the PR #6 base head reproduced a completed Today deletion receipt being replaced by a request to delete the next selected clip. The regression expected `Complete(m1)` but observed `Confirm(m2)` (one test, one failure). The shared deletion gate now permits a new deletion only before a deletion starts or after its receipt has been explicitly cleared. The ViewModel regression verifies that requesting and dismissing another deletion preserve the first receipt, that clearing targets the first ID, and that the second clip can be confirmed only afterward. No database schema, ViewModel implementation or dependency changes were needed.

After this repair, all 15 Today host tests passed. The strict README Gradle command passed on Windows with JDK17/SDK36 in 2m 1s (`480 actionable tasks: 31 executed, 449 up-to-date`); 30 host XML reports contain 136 tests, zero failures, errors or skips. Lint, debug APK and instrumentation APK compilation passed. Storage-schema, lifecycle-ownership and APK-policy checks each passed two tests. The original selected-file Bash scan passed using Git Bash, and the diff whitespace check passed.

Root context/project checks passed when invoked with Python. The full root Make gate could not run because Make is unavailable; direct Windows execution of its test suite encountered native POSIX-script execution errors and an untracked IDE cache directory. A source-only snapshot containing this repair passed the seven root skeleton tests (six passed, one expected older-Python-only skip). This does not replace the full root gate, which remains pending a POSIX/CI run for the repair. The optional Swift/AppKit check and device/instrumentation execution were not run. Camera, microphone synchronization, playback/deletion, BLE and HIL remain pending; no signed release is claimed.

On 2026-09-08, after explicit product confirmation, the local rescue path added foreground microphone buffering and AAC-LC audio muxing. Host tests cover bounded PCM ownership, missing-audio rejection, paired video/audio teardown, permission failure visibility, and a synthetic AAC/H.264 two-track decoder test compiled for instrumentation. The targeted strict project checks and lint passed. The final full strict README command passed on Windows in 1m 40s (`480 actionable tasks: 44 executed, 436 up-to-date`). The 30 host JUnit XML reports contain **135 tests, zero failures, zero errors and zero skipped**. Storage-schema, lifecycle-wiring and APK-policy scripts each passed two tests; `git diff --check` and the selected-file secret scan passed. Instrumentation compiled but was not installed or executed, so device audio capture and actual gallery playback remain pending.

On 2026-09-08, three test-first host failures reproduced stale playback after the displayed moment changed, playback retained during deletion, and a pending deletion target being overwritten. Today now exposes all saved catalog candidates with explicit selection. Seven added host tests cover playback release, fixed confirmation identity, pending-delete exclusion, older selection surviving new saves, selection changes during confirmation, stale provider responses, and retry/record-clearing identity. Two Compose tests cover older-row selection and confirmation identity; one Room integration test covers newest-first ordering and deleting only an older selected row. These three Android tests compiled but were not executed.

The full strict README Gradle command with `--no-configuration-cache` passed on Windows in 6m 24s (480 tasks: 71 executed, 4 from cache, 405 up-to-date). The 26 host JUnit XML reports contain 127 tests, zero failures, errors or skipped tests. Storage-schema, lifecycle-wiring and APK-policy scripts each passed two tests. The selected-file secret-pattern scan used the same patterns via PowerShell/ripgrep; it and `git diff --check` passed. The existing Room schema and dependency verification configuration were unchanged by this fix. No device media was accessed or deleted, and delivered-APK playback/deletion remains pending device verification.

On 2026-09-07, host regressions reproduced rolling-window readiness flicker with synthetic 133/167 ms camera cadence, silent coverage/encoding failure feedback, and delayed button disabling. The implementation now uses a camera-domain snapshot endpoint with a separate host-clock receipt-age check, consistent 250 ms edge/interior continuity bounds, immediate duplicate-action suppression and nearby result feedback. Six new tests cover cadence, clock-origin offset, stale/wiped buffers (including the 250 ms freshness boundary), a real gap invalidating previously complete coverage, failure visibility across warmup updates, and rapid duplicate rescue actions.

The full strict README Gradle command with `--no-configuration-cache` passed on Windows in 3m 13s (480 tasks: 64 executed, 416 up-to-date). The 26 host JUnit XML reports contain 120 tests, zero failures and zero errors. Storage-schema, lifecycle-wiring and APK-policy scripts each passed two tests. This is incremental host/build evidence. A preceding media instrumentation attempt stopped during test APK installation, with zero tests executed; subsequent device operations were not executed. CameraX-to-codec-to-MediaStore behavior on the delivered APK remains pending. Installation timestamps alone were not used as proof of the installed source revision.

Test-first failures demonstrated that the simulator must not request Bluetooth, the application must use a distinct sandbox, the fresh database must contain only local capture storage, exported filenames must use the local-camera namespace, generated color bars must actually decode to colors, and the packaged app must exclude private data from device transfer. Those checks are green for this package. Retained local save, deletion retry/idempotency, capture, session and playback tests remain meaningful; removed network/policy features have no test-count claim here.

Room storage SQL is exercised on host SQLite. The new Android Room DAO lifecycle test and launcher/permission tests are compiled only. A passing host schema or packaged-manifest test does not certify Android backup behavior. The backup configuration follows [Android's backup and transfer controls](https://developer.android.com/identity/data/autobackup); gallery apps may independently handle saved MediaStore clips.

Additional failing-then-passing host regressions cover real capture/moment controllers through SessionViewModel for stop → confirmed restart → new coverage → rescue; permission/simulator notices followed by save progress/success/failure; cancellation of a pending start on background; and Hold/Manual superseding old simulator deadlines. Domain tests also show that restart waits for older encode/save work and retains unresolved cleanup.

Two additional failing-then-passing tests use real Session/capture/moment orchestration with a stateful sampler at the external camera boundary. They cover completed stop → fresh binding → restart waiting for old encode → Today/background stop, verify the newest binding is torn down, and verify old encode completion cannot restart it. Cancellation during a partial host binding also tears it down before completing. The actual SessionRuntime uses the same controller binding boundary; these are host regressions, not device CameraX execution.

The staged-file integration test uses an actual temporary file, the production record-to-Room-entity mapping, and LocalDeletionCoordinator/AppPrivateStagingStore. Save/discard failure leaves a linked cleanup-pending row; a failed Today staging deletion stays retryable without a completed receipt; retry removes the file before completion. The DAO adapter itself is exercised by the compiled-only Room integration test. Today exposes the persisted cleanup link after repository recreation.

Five additional failing-then-passing host regressions cover gallery publication followed by catalog failure. Four coordinator tests verify retained URI/staging metadata, no premature cleanup/completion, one encode/publication across catalog retry, rejected abandon/new rescue, stop/restart, and cancellation while catalog insertion is suspended. A SessionViewModel test uses the real coordinator and verifies the distinct catalog-retry status survives notices and stop/restart without offering full-save abandonment. These tests inject failure at the catalog port; they do not execute Android Room/MediaStore or establish process-death recovery.

`RuntimeRecreationTest` and `MomentPlayerLifecycleTest` compile checks for retained owners across Activity recreation and a surviving PlayerView attaching the next player after background/foreground/play. Their runtime assertions have **not** been executed. The surface test's synthetic URI checks player identity/attachment, not decoded video correctness. No runtime recreation, frame-rendering, or device-behavior pass is inferred from compilation.

The codec instrumentation duration assertion currently checks input-derived `encoded.durationUs`, not measured duration from the generated MP4. Measuring actual output duration remains deferred to device-validation preparation; this revision adds no encoded-duration measurement or device-execution claim.

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
| Microphone capture, audio/video sync, AAC encoding/decoding and system microphone silencing | pending device execution; host guards and compiled instrumentation only |
| Face detector, automatic privacy masking, single-photo capture | not implemented; no validation claim |
| Signed release / store delivery / safety certification | not performed or claimed |

Before running instrumentation, obtain device-operation authority and configure an appropriate API29+ target. From `src`, `./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest` is the execution entrypoint. Root protocol/conformance and public placement checks belong to repository integration; passing this Android build is not canonical L2 conformance or upstream acceptance.
