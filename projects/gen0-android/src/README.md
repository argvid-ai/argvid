# Android source

Open this directory as the Gradle project. See [setup and commands](../README.md). JDK17, Android SDK36 and the checked-in Gradle9.3.1 wrapper are required; minSDK29. No runtime keys or accounts are used.

Modules: `app`, `core/domain`, `adapter/capture`, `adapter/gimbal`, `data/media`, `feature/session`, `feature/today`, `testing/fixtures`. Their unit tests reside in `src/test`; Android integration/UI tests reside in `src/androidTest`. Only the latter require a device; compiling them is not executing them.

The app's `ai.argvid.gen0.camera` sandbox holds a fresh two-table Room version-1 database. Database schema JSON is generated storage metadata, not canonical L2. Gimbal types are semantic simulation only. Caches, local SDK paths, APKs and generated reports are ignored and must not be published.
