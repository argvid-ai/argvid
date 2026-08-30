# Contributing

Begin at [START_HERE.md](START_HERE.md). Reading, building, local modification, and forking for yourself require no Ready Issue or maintainer approval; see [using Argvid](docs/USING.md). Agents modifying code still read technical context and complete the handshake. Remote contribution requires authorized Write access or the public fork/PR route.

## When to confirm scope

Ordinary small fixes, such as a typo, documentation clarification, or bounded bug fix without reserved impact, may directly prepare a PR. An Issue is optional, not a prerequisite.

Larger upstream features, security-sensitive changes, protocol changes, and new upstream projects require maintainer-confirmed scope before implementation, either in an Issue or a maintainer-approved public-safe task brief. A Ready Issue is one way to record this confirmation, not the only way. A contributor cannot self-authorize reserved work by writing a brief; absent approval, stop and request a decision. Shared-contract/architecture changes still require root RFC review, and governance changes still require maintainer approval. Security reports follow [SECURITY.md](SECURITY.md), not public issues.

## Workflow

1. Describe one observable outcome, non-goals, and acceptance checks; obtain scope confirmation when required above. Use the concise [task brief](docs/TASK_HANDOFF.md#task-brief) for assigned work.
2. State the [public inclusion basis](docs/REPOSITORY_SCOPE.md), data/licenses, six-layer/four-plane impact, and required approvals.
3. Work on a small independent branch. Add behavior tests or fixtures first.
4. Use root RFC/conformance for contract changes; no project-local schema copies.
5. Run `make doctor context-check project-check test` and task-specific checks. Record actual commands, results, and unrun tests.
6. Review [publication/IP boundaries](docs/PUBLICATION_POLICY.md) before the first public push. Branches, draft PRs, and CI output are already public.
7. Request maintainer review and leave a reproducible [completion report](docs/TASK_HANDOFF.md#completion-report), including Git/PR state and the next required decision.

See [contributor collaboration](docs/CONTRIBUTOR_WORKFLOW.md) for app/device/integration roles and [project activation](docs/PROJECTS.md) for complete public projects.

Assignment and coordination may happen off-platform. No full chat transcript or Agent prompt upload is required. Put durable behavior, interface, build, and testing facts in public docs/PRs so review and reproduction do not depend on a private conversation. A task packet cannot override accepted architecture or grant publication permission. Keep recipient/personnel details in reports outside the repository.

## Expectations

Keep PRs small. No credentials, restricted or personal data, or material without publication rights. Dependencies need separate source, weight, dataset, asset, and hardware license review. Do not treat Apache-2.0 as permission to redistribute imported materials.

Distinguish structure, conformance, simulation, host, and hardware evidence. Unrun HIL is pending. Do not bypass checks or protected-branch controls. CODEOWNERS routes review; enforcement depends on repository settings and actual approvals.

## Conduct

Be precise, kind, and evidence-driven. Review the change, not the person. Security reports follow [SECURITY.md](SECURITY.md) and must not be posted publicly.
