# Public project framework

A project is a complete public delivery, not necessarily a reference. It must satisfy [scope](REPOSITORY_SCOPE.md), [publication policy](PUBLICATION_POLICY.md), and root contracts. No actual application or device project is activated by this framework.

## Activation and required files

Copy [the template](../projects/_template/README.md) to an immediate `projects/<project-id>/` directory only through an approved Ready Issue. Use a lower-case hyphenated identifier matching the directory. Read and update README, PROJECT_CONTEXT, BRIEF, project.yaml, architecture.md, THIRD_PARTY.md, and the src/tests/fixtures/docs READMEs.

The README explains public purpose, setup, existing entrypoints, verification commands, limitations, and activation authority. A documentation-only project may use README.md as an entrypoint if its public delivery and checks are explicit. All projects require nonempty implemented layers and entrypoints.

## Manifest syntax

`tools/project-check [PROJECT_DIRECTORY]` validates a focused immediate project directory, or all immediate projects with no argument (including a required `_template`). Relative arguments resolve from the caller's directory; project paths must remain in this repository and cannot contain traversal. Exit status: 0 valid, 1 invalid/missing project, 2 invalid usage. Missing paths are named in diagnostics.

The manifest is a deliberately small YAML-compatible subset, not general YAML: one top-level key per line, scalar strings and single-line flow lists only. Blank lines and whole-line comments are allowed. Keys are unique and exactly:

| Key | Meaning |
|---|---|
| project_id | Lower-case hyphenated directory identifier |
| status | Public lifecycle description; not approval evidence |
| license | Declared source license, with notices as applicable |
| protocol_version | `pre-alpha` at the current unversioned stage |
| layers | Implemented identifiers from L4, L3, L2, L1.5, L1, L0 |
| cross_cutting_planes | Implemented Media, Transport, Evaluation, Data Governance planes |
| entrypoints | Nonempty existing paths relative to this project |
| verification | Nonempty commands documented verbatim in README |
| fixture_policy | Public fixture policy; synthetic-only by default |
| third_party_review | Review requirement/status; supporting records in THIRD_PARTY |

Plain strings may contain letters, numbers, spaces, dot, underscore, slash, colon, plus, equals, at-sign, and hyphen. For other characters use a JSON-style double-quoted string. Lists contain these strings separated by commas, with no trailing comma. Empty list syntax is `[]`. Nested values, duplicate or unknown keys, multiline values, YAML quoting/aliases/tags, and inline comments are rejected. Required scalars cannot be blank. Commands are data: the checker never executes them.

The template's exact defaults are example-project, proposed, Apache-2.0, pre-alpha, four empty lists, synthetic-only, and required. It is not a release. The current gate rejects other protocol identities: to introduce a release, first approve and document its immutable protocol identity through root versioning/RFC review, then update the gate with tests and human review. A version label alone proves no compatibility.

## Architecture and shared contracts

architecture.md uses one table row per exact canonical layer name and per plane, with columns Area, Status, Rationale. Status is `implemented` (delivered here), `reused` (public dependency with named identity/path), or `not-applicable` (explained omission). List only implemented layers/planes in the manifest; table and manifest must agree. Include nonblank rationale for every row. All six layers and four planes remain explicit, even when L3 is not applicable.

Use root [protocol](../protocol/README.md), [versioning](../protocol/VERSIONING.md), and [conformance](../conformance/README.md). A project may propose a protocol change via root RFCs and fixtures; it may not copy `protocol/schema` at any depth.

An architecture layer does not by itself choose a root or project path. Project-specific L4, L3, L1.5, L1, and L0 implementations may live in `projects/<project-id>/src` with an explicit layer mapping. Put only reusable shared implementations in the corresponding root modules. Canonical L2 schemas stay in root `protocol`; RFC documents belong in `docs/rfcs`, accepted ADRs in `docs/decisions`, and reusable conformance tests in root `conformance`. Never duplicate root protocol.

## Gates and limits

```bash
make doctor context-check project-check test
./tools/project-check projects/_template
```

Entrypoints must exist, be relative, avoid traversal, and resolve inside the project. Directory symlinks are forbidden, including a symlink used as the project directory itself: directory aliases must not hide a logical protocol/schema path. Use ordinary directories. File symlinks remain allowed only when they resolve inside the project; escaping symlinks and symlink cycles are rejected. Root tool scratch and generated caches are excluded from repository scans; project source/docs are always checked. The checker verifies documented commands but does not run manifest commands or prove they test the claimed behavior.

`make test` includes both structural gates, regression tests, and any actual conformance tests. The current conformance directory is a documented placeholder; passing structure is not conformance or HIL success.
