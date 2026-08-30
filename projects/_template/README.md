# Proposed project template

This is a template, not an active project, release, or compatibility claim. It contains no app/device implementation. Do not change its proposed defaults to activate work here.

## Public purpose and activation

For a new upstream project, obtain maintainer-confirmed scope via an Issue or maintainer-approved public-safe task brief before copying this directory into a named immediate project directory. Personal local experimentation needs no upstream approval but does not activate an accepted project. A contributor cannot self-authorize activation by writing a brief. Record the public purpose, inclusion basis, authorized objective/non-goals, acceptance criteria, and review evidence. Follow [project requirements](../../docs/PROJECTS.md), [repository scope](../../docs/REPOSITORY_SCOPE.md), and [publication policy](../../docs/PUBLICATION_POLICY.md).

## Context and files

Read [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md), [BRIEF.md](BRIEF.md), [project.yaml](project.yaml), [architecture.md](architecture.md), and [THIRD_PARTY.md](THIRD_PARTY.md). After copying, replace instructions with project-specific public facts and explicitly map all layers/planes. Project switches require this context, not repeated credential setup.

Use [task handoff](../../docs/TASK_HANDOFF.md) for assigned work and completion reports. Off-platform assignment supplies only public-safe task content, not confidential context; do not upload full chats or Agent prompts. Public docs/PRs retain durable behavior, interface, build, and testing facts. Reports with recipient/personnel details stay outside the repository.

- [src](src/README.md): delivered source and public entrypoints.
- [tests](tests/README.md): actual behavioral tests and execution evidence.
- [fixtures](fixtures/README.md): synthetic inputs and expected results.
- [docs](docs/README.md): setup, usage, decisions, and public handoff.

## Setup, entrypoints, and verification

The template requires Git, Python 3.12+, Make, and a POSIX-compatible shell. These commands are tested with macOS/POSIX tools and Python 3.12+; CI uses its configured environment. They do not claim native Windows support or prescribe installation procedures. From the repository root:

```bash
make doctor context-check project-check test
./tools/project-check projects/_template
```

There are no implemented entrypoints and no project verification commands in the template manifest. A real project must declare existing relative entrypoints and document each nonempty verification command verbatim here, including environment, inputs, expected results, and tests not run. A documentation-only public delivery may use README.md as an entrypoint.

## Contracts, licensing, and limitations

Reuse root protocol/conformance and propose contract changes through root RFCs. A task packet cannot override accepted architecture or accept a protocol draft; missing required authority stops work. Never copy protocol/schema into a project. GitHub CLI is an optional separately installed authentication route; credential managers and IDE authorization remain alternatives. Never put credentials or tokens in Agent chat. The manifest's pre-alpha value names the unversioned stage, not a released guarantee.

Source/documentation defaults to Apache-2.0. Fixtures are synthetic-only; third-party review is required before adding dependencies, weights, assets, data, or hardware sources. No publication/IP approval, safety certification, hardware validation, or release is granted by copying this template.
