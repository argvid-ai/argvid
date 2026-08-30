# Governance

## Principles

Contracts, tests, and accepted decisions are the engineering authority. Public scope is determined by purpose, reproducibility, and publication rights. Safety, licensing, architecture, and compatibility changes require explicit human review.

## Maintainers and review

Maintainers triage issues, review changes, and record acceptance decisions through public issues, PRs, RFCs, and ADRs. Contributors can own app, device, or integration work without any organizational role. Repository access must be authorized separately; Write access does not grant publication/IP permission.

CODEOWNERS routes review requests to public maintainers. It does not alone enforce required review: repository protection settings and actual review evidence must be checked. Do not claim review enforcement from the file alone or bypass a failing gate.

## Decisions and releases

Small reversible changes use approved Ready Issues and scoped PRs. Contract/architecture changes require root RFCs and accepted ADRs when durable. Security reports follow [SECURITY.md](SECURITY.md).

A template or temporary integration branch is not a release. The protocol is currently `pre-alpha`, an unversioned stage. Any released compatibility claim requires a documented, approved immutable protocol identity, passing applicable conformance checks, explicit limitations, changelog/version notes, and human review. Unrun hardware checks remain pending.

Review [publication policy](docs/PUBLICATION_POLICY.md) before the first public push, including branches, draft PRs, and CI output. Governance changes require a public PR, rationale, and maintainer approval.
