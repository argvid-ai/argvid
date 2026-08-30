# Agent instructions

These rules apply to humans and coding agents working in this public repository. Reading, building, and forking for oneself need no Ready Issue or maintainer approval. Agents modifying code still complete the technical context and handshake below. Choose local use or upstream contribution in [START_HERE.md](START_HERE.md).

## Required context order

START_HERE.md → AGENTS.md → PROJECT_CONTEXT.md → docs/ARCHITECTURE.md → BRIEF.md → nearest module/project context → applicable RFC/ADR → task scope (Issue or task brief)

Read the files rather than relying on conversation memory. The nearest module context is its README and local AGENTS if present. Project context includes README, PROJECT_CONTEXT, BRIEF, project.yaml, architecture.md, THIRD_PARTY.md, and relevant docs. Use only this repository and the task's approved public references; do not search parent directories or other projects. Conflicts or missing approvals stop implementation. [Context authority](docs/context-map.md) explains precedence.

## Handshake fields

Report each field explicitly before implementation:

- `repository_identity`
- `workspace_type` (`root` | `module` | `project`)
- `current_branch_and_commit`
- `authoritative_files_read`
- `task_goal`
- `non_goals`
- `affected_layers`
- `affected_cross_cutting_planes`
- `public_inclusion_basis`
- `data_and_license_classification`
- `planned_files`
- `required_tests`
- `architecture_conflicts`
- `approval_required`

Report the placement result separately, using exactly one outcome from [repository scope](docs/REPOSITORY_SCOPE.md). Exclusion stops work. Authentication and repository access are not publication permission; an Agent cannot grant publication/IP approval. Re-handshake when the repository, branch, task, project, or authoritative context changes.

## Project preimplementation supplement

Before implementing in a project workspace, report this supplement in addition to every root handshake field above. For each item, `none` or `not-applicable` is allowed only with a reason.

- `covered_layers`
- `root_module_reuse_points`
- `new_protocol_requirements` (or `none`)
- `conformance_impact`
- `public_dependency_completeness`: identities and paths, with no missing inaccessible requirements
- `concrete_verification_entrypoints`
- `potentially_promotable_common_capability` (or `none`)

## Invariants

- Preserve the six layers and four cross-cutting planes in [architecture](docs/ARCHITECTURE.md).
- L2 expresses generic intent, capability, target, state, and evidence; never device-specific motor commands.
- L1.5 validates, schedules, preempts, and manages state/resources without changing L2 semantics or replacing L0 safety.
- L0 deterministic limits, emergency stop, and watchdog behavior cannot be overridden by model output.
- Capability mismatches are explicit; no silent downgrade.
- Public implementations and tools must be self-contained and reproducible with publicly available inputs.
- No credentials, restricted data, personal media, or material without publication rights may enter public artifacts.
- Unrun hardware-in-the-loop checks remain `pending`, never `passed`.

## Change discipline

Use small branches and an explicit task scope. Local modifications need no upstream approval. Ordinary small upstream fixes may directly prepare a PR; larger features, security-sensitive changes, protocol changes, and new upstream projects require maintainer-confirmed scope through an Issue or maintainer-approved public-safe task brief. A contributor or Agent cannot self-authorize these reserved changes by writing a brief. Missing required authority stops implementation; security reports follow [SECURITY.md](SECURITY.md).

Tests precede behavior changes; contract changes still use root RFCs, fixtures, and conformance, not project-local schemas. A draft RFC or task packet does not override accepted architecture or authorize contract acceptance. Do not activate upstream placeholder implementations without their documented start conditions. Review publication before the first public push, including draft PR and CI output. Do not bypass checks or protected-branch controls.

Assignment may happen off-platform. Do not require or upload full chats/Agent prompts; retain durable behavior, interface, build, and testing facts in public docs/PRs. Use [task handoff](docs/TASK_HANDOFF.md) for the brief/report boundary. Recipient-specific reports with personnel details belong outside the repository.

## Completion report

Use the [generic report fields](docs/TASK_HANDOFF.md#completion-report): task/source revision, changed paths, tests/evidence, not-run checks, local/remote Git state and PR link, interface compatibility, blockers, next action, and required decision. Promote durable decisions to an RFC/ADR and update context when needed. Automated gates do not replace human publication, licensing, compatibility, or safety review.
