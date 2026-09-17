# Project brief

Status: delivered on branch `codex/gen0-gimbal-driver` for review; not accepted and not released.

## Authorized scope

Objective: deliver the Gen0.5 gimbal driver, control, and app-consumable device interface as a scoped, reviewable change, per the maintainer-approved public-safe task brief for Gen0.5 gimbal driver/control (2026-08). The brief grants branch/PR rights; merging `main` remains with maintainers.

Non-goals: no canonical L2/wire protocol invention or adoption; no activation of the `adapters/gimbal-gen05` or `firmware/gen05-gimbal` placeholders (their documented start conditions are not met); no hardware safety certification; no release compatibility claim; no vendor manual or vendor example redistribution.

## Acceptance checks

- [x] Driver/control source and tests delivered (firmware, app, tools) with no simulator code presented as physical-device implementation.
- [x] Hardware identity, supported capabilities, and limits documented without device serial numbers.
- [x] Command, state, ack/timeout, error, and capability-mismatch semantics documented in [docs/README.md](docs/README.md).
- [x] Safety boundaries and unverified items recorded (README, architecture).
- [x] Reproducible build/flash steps and host verification commands in README; `flutter test` and `python -m py_compile` pass on the host.
- [ ] Real-device flash, BLE end-to-end integration, and HIL motion tests: pending.
- [ ] Publication/IP, safety, dependency licensing, and compatibility review: pending maintainer review.

## Reviews required

Safety and hardware review for the firmware limits and fail-safe behavior; protocol review before any contract adoption; dependency licensing review per THIRD_PARTY.md; publication review before merge.

## Handoff

App-side integration work proceeds against the documented JSON command/event contract in [docs/README.md](docs/README.md) at a fixed revision of this branch. A live interface handoff (PR URL, commit, device identity, test evidence) is provided through the task handoff channel outside the repository. App or model logic does not replace L0 safety.
