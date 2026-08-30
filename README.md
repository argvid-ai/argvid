# Argvid

Argvid is a public, self-contained framework for intent-driven active photography: shared contracts, reproducible references, validation, and complete public projects.

> Status: pre-alpha architecture skeleton. No stable schemas, released compatibility guarantee, device implementation, or hardware-in-the-loop safety validation is claimed.

## Start here

Begin with [START_HERE.md](START_HERE.md), then follow its context order. New Agent conversations can use the [Chinese quickstart prompt](docs/AGENT_QUICKSTART.md). Reading public code requires no account or credentials.

```bash
make doctor context-check project-check test
```

These checks use Python 3.12+ standard library and Git, run offline, and need no credentials.

## Public scope

Inclusion is based on stable interoperability contracts, reproducible reference implementations, conformance/safety/performance validation, complete public project delivery, or open-source governance/docs/tooling. A complete public application or device project can belong here; it need not be only a reference. Publication rights and reproducibility must be reviewed first. See [repository scope](docs/REPOSITORY_SCOPE.md) and [publication policy](docs/PUBLICATION_POLICY.md).

## Architecture and repository map

[Architecture](docs/ARCHITECTURE.md) defines L4 Experience, L3 Decision, L2 Contract, L1.5 Orchestration, L1 Adapter, and L0 Execution. Media, Transport, Evaluation, and Data Governance are cross-cutting planes, not extra layers.

| Area | Purpose |
|---|---|
| [protocol](protocol/README.md) | Shared contracts, fixtures, versioning; currently unversioned |
| [conformance](conformance/README.md) | Compatibility and safety checks |
| [reference-director](reference-director/README.md) | Reproducible decision reference |
| [adapters](adapters/README.md) | Contract-to-device translation boundaries |
| [firmware](firmware/gen05-gimbal/README.md), [hardware](hardware/gen05-lamp/README.md) | Deterministic execution and reproducible hardware boundaries |
| [projects](projects/README.md) | Complete public project deliveries; currently only a proposed template |
| [media](media/README.md), [bench](bench/README.md), [examples](examples/replay/README.md) | Public assets, measurements, and runnable examples when activated |
| [context map](docs/context-map.md) | Authority and context maintenance |

Module placeholders describe their responsibilities and start conditions; they are not implemented features.

## Contributing and license

Read [CONTRIBUTING.md](CONTRIBUTING.md), [GOVERNANCE.md](GOVERNANCE.md), and [SECURITY.md](SECURITY.md). Review before the first public push: branches, draft PRs, logs, and CI output are already public.

Source code and documentation use [Apache-2.0](LICENSE) unless explicitly stated otherwise. Hardware design source requires a reviewed license before publication; no hardware source release is implied.
