# Agent instructions

These rules apply to humans and coding agents working in this public repository.

## Required context order

START_HERE.md → AGENTS.md → PROJECT_CONTEXT.md → docs/ARCHITECTURE.md → BRIEF.md → nearest module/project context → RFC/ADR/Ready Issue

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

Use an approved Ready Issue and small branches. Tests precede behavior changes; contract changes use root RFCs, fixtures, and conformance, not project-local schemas. Do not activate placeholder implementations without their documented start conditions. Review publication before the first public push, including draft PR and CI output. Do not bypass checks or protected-branch controls.

## Completion report

Report files, source revisions, actual commands/results, checks not run, evidence, remaining issues, and next step. Promote durable decisions to an RFC/ADR and update context when needed. Automated gates do not replace human publication, licensing, compatibility, or safety review.
