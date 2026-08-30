# Current brief

Updated: 2026-08-30

Phase: public framework, pre-alpha

Protocol stage: `pre-alpha` (unversioned; not a release)

## Current objective

Make the public repository understandable and independently checkable, including its experimental local-camera project, while keeping shared-contract and device-validation claims appropriately bounded.

## Fixed boundaries

- Six canonical layers and four planes are defined in [architecture](docs/ARCHITECTURE.md).
- Contracts, reproducible references, validation, complete public project delivery, and governance/tooling are valid inclusion bases.
- Default source/documentation license is Apache-2.0; hardware source publication needs a reviewed license.
- Shared protocol changes use root RFCs, fixtures, and conformance.
- Safety logic is deterministic and fail-safe.
- Public branches, draft PRs, and CI output require publication review before the first push.

## Now

- [x] Document repository scope, contributor onboarding, and project activation.
- [x] Provide a proposed project template and offline context/project gates.
- [x] Integrate the experimental [Gen0 Camera for Android](projects/gen0-android/README.md) with public build instructions and host verification.
- [ ] Accept the first contract RFC with valid/invalid synthetic fixtures.
- [ ] Implement a conformance runner against approved contracts.
- [ ] Activate replay and simulation/host-test work through maintainer-confirmed scope via Issues or approved public-safe task briefs, with documented start conditions met.

## Pending, not claimed

No stable public API or released protocol compatibility; no hardware-in-the-loop validation; no production safety certification. The experimental Android project does not establish canonical L2 semantics, physical BLE, or device acceptance. Hardware source licensing, capability granularity, and evidence publication/storage remain review decisions.

## Handoff

The next contract task should bound a public proposal and synthetic acceptance cases through maintainer-confirmed scope via an Issue or approved public-safe task brief; root RFC review still governs acceptance. The template alone grants no upstream implementation, publication, or release permission.
