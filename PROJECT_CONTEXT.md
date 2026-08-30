# Project context

## Mission and public scope

Argvid separates capture intent from device execution so public applications and devices can interoperate safely and observably. This repository contains shared contracts, reproducible references, validation, complete public projects, and governance/tooling. It has no dependency on unpublished context. [Scope](docs/REPOSITORY_SCOPE.md) and [publication policy](docs/PUBLICATION_POLICY.md) govern inclusion.

## Canonical layers

| Layer | Responsibility | Must not do |
|---|---|---|
| L4 Experience | User interaction, task, result | Drive hardware directly |
| L3 Decision | Scene understanding, composition, plan | Direct device commands or safety bypass |
| L2 Contract | Intent, capability, target, state, evidence | Device-specific execution |
| L1.5 Orchestration | Validation, scheduling, preemption, state, resources | Change L2 semantics or substitute for L0 safety |
| L1 Adapter | Translate generic contracts to device operations | Silently downgrade capabilities |
| L0 Execution | Deterministic control, limits, emergency stop, watchdog | Rely on model output as a safety guarantee |

## Cross-cutting planes

- Media: frames, descriptors, timestamps, and sample handling across layers.
- Transport: message delivery, connection lifecycle, ordering, and failure visibility.
- Evaluation: conformance, safety, performance, and reproducible evidence.
- Data Governance: provenance, licensing, privacy, retention, and publication permission.

Planes are not additional layers. See [architecture](docs/ARCHITECTURE.md) for dependency boundaries.

## Current stage

The protocol is `pre-alpha`: an unversioned stage, not a released compatibility guarantee. The repository has documented placeholders and structural gates alongside the experimental, local-camera [Gen0 Android project](projects/gen0-android/README.md). That project does not establish a stable protocol, device implementation, or hardware safety claim. [The brief](BRIEF.md) defines current priorities.

A replay-to-evidence path and a bounded gimbal simulation/host-test path are proposed initial validation slices. They must share root protocol fixtures and conformance semantics. Hardware-in-the-loop validation is pending.

## Authority

Knowledge moves from proposal to reviewed, accepted, and eventually deprecated. A proposal or template is not an implementation or release. Accepted decisions and current contracts/tests outrank summaries; stop on conflicts.
