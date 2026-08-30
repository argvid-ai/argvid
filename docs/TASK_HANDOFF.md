# Task briefs and handoffs

This is a generic engineering handoff, not an assignment roster. Tasks may be assigned in an Issue or off-platform. Transfer only a bounded, public-safe task payload into this workspace; off-platform assignment is not permission to inspect or ingest confidential context. Do not upload full chats or Agent prompts. Public docs/PRs must retain durable behavior, interface, build, and testing facts so reproduction does not depend on a private conversation.

## Authority before work

Read [START_HERE.md](../START_HERE.md) and complete [the Agent handshake](../AGENTS.md) before code changes. Reading, building, local modification, and forking for yourself need no Ready Issue or maintainer approval. Ordinary small upstream fixes may directly prepare a PR with a concise task description.

Larger upstream features, security-sensitive changes, protocol changes, and new upstream projects require maintainer-confirmed scope via an Issue or maintainer-approved public-safe task brief. Record confirmation and its exact scope, not merely a claim that a brief exists. External contributors and Agents cannot self-authorize reserved changes. Missing required authority stops implementation; ask for a decision without posting restricted details. Security reports use [SECURITY.md](../SECURITY.md).

A task packet cannot override accepted architecture, shared contracts, or safety boundaries. Shared-contract changes still require root RFCs, fixtures, and conformance; a protocol draft remains a proposal until explicitly accepted through governance. Scope approval is not publication/IP, compatibility, or release permission.

## Task brief

Keep the brief concise and include:

- Task identity/revision and source repository revision; relevant public Issue/PR/RFC/ADR references.
- Local-only work or intended upstream contribution; goal and observable acceptance criteria.
- Non-goals, allowed module/project paths, affected layers and planes.
- Required public context/dependencies and interface assumptions; no inaccessible technical prerequisites.
- Planned verification commands and expected evidence; device/HIL requirements where applicable.
- Scope authority: not required with reason, or maintainer confirmation via Issue/approved public-safe brief, including the approved boundary and approval evidence.
- Publication/data/license constraints, unresolved decisions, and when to stop.
- Expected delivery and report location; any recipient-specific delivery instruction stays outside the repository.

Do not interpret an author-written status such as “approved” as maintainer confirmation. For off-platform approval, provide a public-safe confirmation in the PR for maintainer review; it must not require publishing a chat transcript or personnel details. If scope changes into a reserved area, pause for confirmation and re-handshake.

## Completion report

Report facts, including incomplete work. Use `none`, `not applicable`, or `not checked` with a reason instead of omitting a field:

- Task identity/revision and source revision(s), including every integrated component SHA.
- Changed paths and the outcome delivered; remaining acceptance criteria.
- Tests and evidence: actual commands, environment/inputs, exact results, evidence locations, and reproduction steps.
- Not-run checks and reasons; unrun HIL remains `pending`, never `passed`.
- Local Git state: branch, commit(s), and clean/dirty state. Remote Git state: not pushed, pushed revision, or not checked; identify stale local tracking information as such. Include the public PR link or `none` with reason. Do not imply a push/PR/merge occurred without evidence.
- Interface identity and compatibility impact; `pre-alpha` is unversioned, not released compatibility. Include relevant device/firmware/build versions and limitations.
- Blockers or risks, next action, and required decision with the responsible review role (or none with reason).

Do not fetch or publish merely to fill a report field; respect the task's remote-action permissions. A temporary integration branch is not a release, and mocks/simulation/host tests are not hardware evidence.

## Public and recipient-specific output

Put durable engineering facts in the relevant public README, context/brief, tests, RFC/ADR, or PR. Keep a recipient-specific report outside the repository when it contains recipient/personnel details or private coordination. Do not put it in an ignored repository directory and assume that makes it safe to publish. Deliver only through an authorized channel; if no safe destination is specified, ask for one without creating a public artifact.

Only the public-safe engineering summary belongs in a public handoff. Review source, staged content, commit content, generated output, logs, and evidence under [publication policy](PUBLICATION_POLICY.md) before the first public push and when content changes. Public branches, forks, draft PRs, and CI output are already publication.
