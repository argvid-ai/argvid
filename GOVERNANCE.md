# Governance

## Principles

Contracts, tests, and accepted decisions are the engineering authority. Public scope is determined by purpose, reproducibility, and publication rights. Safety, licensing, architecture, and compatibility changes require explicit human review.

## Maintainers and review

Maintainers triage issues, approve task scope, review changes, and record durable acceptance decisions through public issues, PRs, RFCs, and ADRs. Scope confirmation may use an Issue or a maintainer-approved public-safe task brief, including one assigned off-platform. The PR records the public-safe scope and approval evidence; full chats, Agent prompts, and personnel details are not required. Contributors can own app, device, or integration work without any organizational role. Repository access must be authorized separately; Write access does not grant publication/IP permission.

CODEOWNERS routes review requests to public maintainers. It does not alone enforce required review: repository protection settings and actual review evidence must be checked. Do not claim review enforcement from the file alone or bypass a failing gate.

## Decisions and releases

Reading, building, local modification, and forking for oneself need no Ready Issue or maintainer approval. Ordinary small upstream fixes may directly prepare scoped PRs. Larger features, security-sensitive changes, protocol changes, and new upstream projects require maintainer-confirmed scope via an Issue or maintainer-approved public-safe task brief before implementation. External contributors and Agents cannot self-approve reserved changes. Missing required authority stops work.

Contract/architecture changes require root RFCs and accepted ADRs when durable, regardless of the scope-confirmation route. A task packet, proposal, or draft RFC cannot override accepted architecture, accept a protocol draft, or grant release/publication authority. Security reports follow [SECURITY.md](SECURITY.md).

A template or temporary integration branch is not a release. The protocol is currently `pre-alpha`, an unversioned stage. Any released compatibility claim requires a documented, approved immutable protocol identity, passing applicable conformance checks, explicit limitations, changelog/version notes, and human review. Unrun hardware checks remain pending.

Review [publication policy](docs/PUBLICATION_POLICY.md) before the first public push, including branches, draft PRs, and CI output. Governance changes require a public PR, rationale, and maintainer approval.
