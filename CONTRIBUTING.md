# Contributing

Begin at [START_HERE.md](START_HERE.md) and complete the context handshake before changing files. Reading public code and running checks require no account; remote contribution requires authorized Write access or the public fork/PR route.

## Workflow

1. Use an approved Ready Issue with one observable outcome, non-goals, and acceptance checks.
2. State the [public inclusion basis](docs/REPOSITORY_SCOPE.md), data/licenses, six-layer/four-plane impact, and required approvals.
3. Work on a small independent branch. Add behavior tests or fixtures first.
4. Use root RFC/conformance for contract changes; no project-local schema copies.
5. Run `make doctor context-check project-check test` and task-specific checks. Record actual commands, results, and unrun tests.
6. Review [publication/IP boundaries](docs/PUBLICATION_POLICY.md) before the first public push. Branches, draft PRs, and CI output are already public.
7. Request maintainer review and leave a reproducible handoff.

See [contributor collaboration](docs/CONTRIBUTOR_WORKFLOW.md) for app/device/integration roles and [project activation](docs/PROJECTS.md) for complete public projects.

## Expectations

Keep PRs small. No credentials, restricted or personal data, or material without publication rights. Dependencies need separate source, weight, dataset, asset, and hardware license review. Do not treat Apache-2.0 as permission to redistribute imported materials.

Distinguish structure, conformance, simulation, host, and hardware evidence. Unrun HIL is pending. Do not bypass checks or protected-branch controls. CODEOWNERS routes review; enforcement depends on repository settings and actual approvals.

## Conduct

Be precise, kind, and evidence-driven. Review the change, not the person. Security reports follow [SECURITY.md](SECURITY.md) and must not be posted publicly.
