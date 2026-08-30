## Outcome

Describe the verifiable outcome. For an ordinary small fix, an Issue is optional. For larger features, security-sensitive changes, protocol changes, or a new upstream project, link the maintainer-confirmed Issue or summarize the maintainer-approved public-safe task brief and approval evidence. A self-written brief is not approval; shared-contract changes still require root RFC review. Do not upload chats, full Agent prompts, or personnel details.

## Scope

State task identity/revision, non-goals, affected layers/planes, changed module/project paths, and source SHAs. A task packet cannot override accepted architecture or accept a protocol draft.

## Verification

- [ ] `make doctor context-check project-check test`
- [ ] Protocol fixtures and conformance updated when semantics changed
- [ ] Simulation, host, and hardware evidence identified separately

Commands and results:

```text

```

## Risk and boundaries

- Placement result: ACCEPT_IN_PUBLIC_REPOSITORY / EXCLUDE_FROM_PUBLIC_REPOSITORY
- Public inclusion basis:
- Data and license classification:
- Publication/IP review completed before the first public push (including draft PR/CI output):
- Required human approvals and evidence:
- Security or safety impact:
- Compatibility impact:
- Checks not run and why:

## Knowledge delta

Which brief, RFC, ADR, module boundary, or changelog entry changed?

## Handoff

Use the completion-report fields in docs/TASK_HANDOFF.md: task/source revision, changed paths, tests/evidence, not-run checks, local/remote Git state and PR link, interface compatibility, blockers, next action, and required decision. Include setup and device/firmware/build versions where applicable. Mark unknown remote state as not checked, not as pushed or merged. Keep recipient-specific reports outside the repository. Unrun HIL remains pending; temporary integration branches are not releases.
