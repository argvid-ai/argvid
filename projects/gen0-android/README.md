# Gen0 Camera for Android

An experimental, local-only camera project: keep a short in-memory proxy buffer, manually rescue a clip into Android MediaStore, and play or delete the latest saved clip in Today. A deterministic gimbal semantic simulator and console components are included for host tests; they do not operate physical hardware.

The saved result is a silent H.264/MP4 proxy video reconstructed from sampled JPEG frames (default 960×540 at 8 fps, targeting the most recent 15 seconds). There is no audio track, single-photo capture flow, or face-detection photography feature. It is not a full-resolution camera/video recorder.

This is not a production release, validated face detector, real BLE implementation, canonical L2 protocol, or hardware safety implementation. No signed release or completed hardware-in-the-loop validation is claimed. The device matrix for this revision is pending.

## Start and scope

Read [START_HERE](START_HERE.md), [project context](PROJECT_CONTEXT.md), [brief](BRIEF.md), [architecture](architecture.md), and [third-party review](THIRD_PARTY.md). Its inclusion basis is a complete public project with reproducible local tests. Local use needs no upstream scope approval; upstream activation and publication remain subject to maintainer scope, rights, licensing, and safety review. This brief does not itself grant those approvals.

## Setup

Install JDK 17 and Android SDK Platform 36 plus Android SDK Build-Tools 36.0.0. Minimum device API is 29; target/compile API is 36. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK location. Alternatively configure an untracked `src/local.properties` with `sdk.dir`. Use a dedicated writable `GRADLE_USER_HOME` if desired; do not commit caches, local paths, credentials, APKs, or build logs. Internet access is needed for the first build's public dependencies; the installed app has no INTERNET permission or service credentials. The wrapper pins Gradle 9.3.1 and its distribution SHA-256. [Verification](docs/verification.md) includes wrapper verification and evidence boundaries.

Open `src` in Android Studio or use its wrapper. The launcher is [MainActivity](src/app/src/main/kotlin/ai/argvid/gen0/MainActivity.kt). The application ID is `ai.argvid.gen0.camera`; Kotlin namespaces use `ai.argvid.gen0`. The app uses a fresh, local-only Room version-1 database in its own sandbox. It does not migrate or erase another app's data.

This revision's recorded build evidence is macOS-only. Strict verification records the Linux AAPT2 checksum and reviewed cold-resolution metadata; consult the [Android workflow result](https://github.com/argvid-ai/argvid/actions/workflows/android.yml) for the exact commit. A local macOS build does not establish Ubuntu verification; see [verification limits](docs/verification.md).

## Verification

From this project directory, with JDK/SDK environment configured:

```bash
./src/gradlew -p src --no-daemon --max-workers=2 --dependency-verification=strict testDebugUnitTest :core:domain:test :testing:fixtures:test lintDebug :app:assembleDebug assembleDebugAndroidTest
python3 tests/test_storage_schema.py -v
python3 tests/test_lifecycle_ownership.py -v
python3 tests/test_apk_policy.py -v
bash src/scripts/check-secrets.sh src tests fixtures docs README.md START_HERE.md PROJECT_CONTEXT.md BRIEF.md architecture.md THIRD_PARTY.md project.yaml
```

From `src`, the equivalent Gradle command is:

```bash
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict testDebugUnitTest :core:domain:test :testing:fixtures:test lintDebug :app:assembleDebug assembleDebugAndroidTest
```

The debug APK is generated at `src/app/build/outputs/apk/debug/app-debug.apk`. Building test APKs does not execute instrumentation. On a separately authorized API29+ device, `./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest` from `src` runs instrumentation; it has not been run for this revision. Do not interpret host tests as CameraX, codec, MediaStore, device performance, or hardware safety validation.

Optional synthetic-image regeneration and pixel verification require macOS with Swift/AppKit, with no external font or image inputs:

```bash
swift src/scripts/generate-proxy-fixtures.swift src/data/media/src/androidTest/assets/proxy-frames
swift tests/check_proxy_fixtures.swift src/data/media/src/androidTest/assets/proxy-frames
```

## Local use and limits

After a separately authorized installation, open Session and request camera permission. Keep the app foregrounded while the buffer warms; rescue becomes available after sufficient coverage. Rescue encodes a proxy-quality clip and reports success only after MediaStore saving succeeds. Today verifies the saved URI before offering playback; deletion asks for confirmation and keeps its metadata receipt until separately cleared. Files use `Movies/Gen0Camera` and a `GEN0_` filename prefix.

STOP clears the capture buffer. After visiting Today, backgrounding, or Activity recreation, returning to Session requires an explicit confirmed start and a new buffer warmup. An existing camera grant is reused for the new binding without another permission dialog. An in-flight encode/save finishes its stop handling before a fresh session begins; unresolved save/cleanup work is not overwritten by another rescue. Permission and simulator notices do not replace saving/success/failure status.

Camera binding and stop share one serialized controller boundary. A fresh binding invalidates the previous stop receipt before camera work starts, so stopping again while restart waits for an old moment tears down the latest binding. Cancelling a partially completed binding also performs finite teardown.

If a clip saves but its private staging copy cannot be removed, Session offers cleanup retry and Today shows that a staging copy remains. The Room record retains the staging path until cleanup succeeds. Confirmed Today deletion removes the gallery asset and that known staging copy; a failure remains retryable and does not receive a completed-deletion receipt. After process death, Today can still delete a saved clip and its linked staging copy, but there is no automatic recovery of unsaved work.

Camera is the only runtime permission requested; library manifests also declare the normal WAKE_LOCK permission and an app-scoped signature permission. Bluetooth and microphone features are absent. The app opts out of Android backup and excludes its private data from device transfer. This does not control independent gallery/photo-backup apps: saved MediaStore clips can be handled by those apps under the user's device settings. Camera frames and saved clips may contain sensitive surroundings: the entrypoint supplies no automatic face masks, and its camera preview is not anonymized. The mask-processing unit tests only exercise explicitly supplied rectangles. Do not use real personal media as repository fixtures.

The simulator models capabilities, acknowledgments, ranges, motion, and stop semantics in process. The Session surface labels its state as simulated; [GimbalConsoleScreen](src/feature/session/src/main/kotlin/ai/argvid/gen0/session/GimbalConsoleScreen.kt) is an independently testable component, not a device-control launcher tab. There is no cloud sync, network provider, reshoot, preset, or decision-model path. Failed-save retry/abandon is available within the running session; there is no background cleanup job or process-death recovery service.
