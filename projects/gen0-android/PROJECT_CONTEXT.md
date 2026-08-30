# Project context

Gen0 Camera delivers foreground local capture/rescue, Android MediaStore persistence, a Room metadata catalog, latest-clip playback/deletion, and deterministic semantic gimbal simulation. Its user problem is retaining and managing a recent short clip without a network service. [README](README.md) is the setup and verification entrypoint.

## Delivery and boundaries

The Gradle build and all eight modules are under `src`. App identity `ai.argvid.gen0.camera` creates a separate sandbox with a fresh local-only Room baseline. Media contains only synthetic numbered color-bar fixtures; no personal recordings, screenshots, model weights, remote endpoints, runtime keys, or hardware design files are required. Dependencies and artifact hashes are public and pinned; their declarations and notices are tracked in [THIRD_PARTY](THIRD_PARTY.md).

Implemented layers are L4, L1.5, L1. Media, simulated Transport, Evaluation, and Data Governance are cross-cutting planes, not extra layers. [architecture.md](architecture.md) maps every canonical row. Root architecture and accepted decisions remain authoritative; no layer or safety constraint is overridden. There is no L3 decision model, canonical L2 implementation, physical BLE, or hardware L0 safety claim.

## Project preimplementation facts

- Root reuse points: architecture and protocol/conformance boundaries only; the pre-alpha root has no accepted schema implementation to invoke.
- New protocol requirements: none. Gimbal Kotlin models are project-specific simulation types; Room JSON is database-storage metadata, not shared protocol.
- Conformance impact: none; no root schemas or fixtures are copied or modified. Passing project tests does not imply root protocol conformance.
- Public dependency completeness: JDK17/Android SDK36 and the pinned public Gradle/Maven components listed in THIRD_PARTY; source paths and fixture generators are present locally. No inaccessible runtime dependency is required.
- Concrete verification entrypoints: README commands, `tests/test_storage_schema.py`, optional `tests/check_proxy_fixtures.swift`, Gradle module unit/lint/build tasks, the repository's pinned-action Android CI workflow, and pending Android instrumentation. The CI workflow does not turn unrun instrumentation or device checks into evidence.
- Potentially promotable capability: deterministic simulator semantics may inform a later root RFC; no promotion or protocol acceptance occurs here.

The project remains experimental. Scope approval, publication/IP permission, licensing review, and release/device acceptance are separate human decisions. Follow the specific task brief; do not import context from other projects or parent workspaces.
