# Local-camera project brief

Status: experimental local implementation; not a signed release or evidence of upstream activation approval.

## Objective and acceptance

Deliver a self-contained Android app with foreground CameraX and microphone sampling, manual audio/video proxy rescue, Room/MediaStore persistence, a selectable saved-clip library with truthful playback/deletion, and reproducible semantic-gimbal host tests. Both camera and microphone grants are required for new capture; missing audio must not silently downgrade a rescue to video-only. Preserve eight Gradle modules under `src` with no runtime service secrets. Use a distinct application sandbox and a fresh two-table local storage schema.

Acceptance checks are the exact unit/lint/debug-build, instrumentation-compilation, storage-schema, fixture, and selected-file scan commands in README. The repository's pinned-action Android workflow runs the strict Gradle host build on Ubuntu; actual CI results and pending device checks are in [verification](docs/verification.md). Unrun device and HIL tests are pending, never passed.

## Non-goals

No cloud/provider path, presets, reshoot suggestions, decision model, canonical L2 schema, physical BLE, automatic face detector, hardware L0 implementation, process-death recovery service, release signing, or production/safety certification. Changes to shared protocols require root RFC and conformance review.

## Scope and review

Allowed project paths are source, tests, synthetic fixtures, dependency notices, and the project documentation. Implemented layers: L4/L1.5/L1; affected planes: Media, Transport simulation, Evaluation, Data Governance. No root module implementation is activated by this project brief.

Reading/building/local modification requires no upstream scope approval. An intended new upstream project requires maintainer-confirmed scope via an Issue or approved public-safe task brief; this document does not self-authorize that acceptance or publication. Before public delivery, reviewers must confirm source/asset rights, dependency licenses/notices, privacy wording, and simulation-versus-hardware boundaries. Missing publication rights stop publication. Keep recipient-specific reports and raw build logs outside the public package.
