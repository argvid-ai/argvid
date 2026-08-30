# Contributor workflow

## Small public delivery boundaries

App contributors work on user interaction, tasks, and results at L4. Device contributors map generic contracts through L1 and enforce deterministic execution at L0. Integration contributors coordinate validation, scheduling, preemption, resources, state, and evidence at L1.5. L2 is shared by all contributors. L3 is optional: an explicit user request may need no scene or composition decision.

```text
app (L4) → optional decision (L3) → shared contract (L2)
                                  ↓
                         orchestration (L1.5)
                                  ↓
                         contract adapter (L1)
                                  ↓
                     deterministic execution (L0)
```

Media, Transport, Evaluation, and Data Governance span the path. Neither app nor integration code may bypass device safety.

An architecture layer does not by itself choose a root or project path. Project-specific L4, L3, L1.5, L1, and L0 implementations may live in `projects/<id>/src` with an explicit layer mapping; reusable shared implementations belong in the corresponding root modules. Canonical L2 schemas remain in root `protocol`. RFC documents belong in `docs/rfcs`, accepted ADRs in `docs/decisions`, and reusable conformance tests in root `conformance`. Never duplicate root protocol.

## Collaboration sequence

1. Start with explicit task scope: ordinary small fixes may directly prepare a PR; larger features, security-sensitive changes, protocol changes, and new upstream projects require maintainer confirmation via Issue or approved public-safe task brief. For contract work, also use root RFC discussion and synthetic valid/invalid cases; agree on capability and failure semantics before parallel implementation. A task packet cannot override accepted architecture or accept a protocol draft.
2. Use small independent branches/PRs with explicit layer/plane mappings and entrypoints. Share public contracts, not copied project-local schemas.
3. Integrate against mocks/simulators first; record source revisions and actual evidence. A mock is not hardware proof.
4. Run host tests, then opt-in hardware tests with exact device/firmware/build versions and safety setup. Mark every unrun HIL check pending.
5. Review publication before the first public push. Obtain review and CI evidence before integration/release decisions.

Temporary integration branches record every source SHA and the interface identity. They help reproduce a combination; they are not releases or compatibility guarantees. Resolve contract changes through root RFC/conformance updates, not a temporary branch's undocumented behavior.

Assignment may happen off-platform using only a bounded public-safe task payload. No full chat or Agent prompt upload is required. Missing required authority stops work; a contributor cannot self-authorize reserved changes. Durable behavior, interface, build, and testing facts belong in public docs/PRs, not private coordination.

## Handoff record

Use the [generic task brief and completion report](TASK_HANDOFF.md), including task/source revision, changed paths, tests/evidence, not-run checks, local/remote Git state and PR link, interface compatibility, blockers, next action, and required decision. For integration work also include:

- Objective and non-goals.
- Module/project paths.
- Source SHAs for every integrated component.
- Interface version; `pre-alpha` means unversioned, not released compatibility.
- Device, firmware, and build versions where applicable.
- Setup, configuration, and synthetic/public input provenance.
- Tests run and exact results; tests not run and reasons.
- Evidence location and reproduction commands.
- Remaining issue and next step.

Do not mark unrun HIL passed. Do not put credentials, restricted logs, or material without publication rights in the handoff. Reports containing recipient/personnel details stay outside the repository; public artifacts contain only the reviewed engineering summary. Project-specific details belong in [project context](PROJECTS.md), not a separate organizational workflow.
