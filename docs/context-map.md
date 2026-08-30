# Context map

## Required context order

START_HERE.md → AGENTS.md → PROJECT_CONTEXT.md → docs/ARCHITECTURE.md → BRIEF.md → nearest module/project context → RFC/ADR/Ready Issue

The root chain establishes public scope and architecture before task details. Module context means its README and any local AGENTS. Project context means README, PROJECT_CONTEXT, BRIEF, project.yaml, architecture.md, THIRD_PARTY.md, and relevant docs. Re-read affected authority after a context change.

## Truth priority

1. Accepted architecture decisions, current shared contracts, fixtures, and conformance tests (released identities when available).
2. Current architecture and repository scope.
3. Current root and project briefs.
4. Approved Ready Issue with acceptance criteria and reviewed RFC.
5. Discussion or Agent memory.

An unresolved conflict stops implementation; a lower-priority summary cannot override a contract or safety boundary. No released schema exists yet.

## Update map

| Change | Update with it |
|---|---|
| Contract meaning | Root RFC/ADR, schemas, fixtures, conformance, protocol changelog and version notes |
| Layer or plane boundary | Architecture, project mapping, module context, review |
| Current objective | Root/project BRIEF |
| Project delivery | Manifest, entrypoints, tests, third-party records, project docs |
| Workflow or permissions | CONTRIBUTING, GOVERNANCE, public templates |
| Security or publication boundary | SECURITY, publication policy, evidence |

## Offline context gate and link syntax

`tools/context-check` takes no arguments and checks the repository containing the script, not the caller's working directory. It returns 0 on success, 1 on invalid context. It checks required files, the exact read chain, canonical layer/plane mappings, placement outcomes, handshake fields, and existing local Markdown link targets contained in this repository.

Supported navigation syntax is an inline Markdown link or image with a simple relative destination and optional fragment, for example `[context](context-map.md#truth-priority)`. Fragments are not validated. HTTPS/HTTP/mailto targets are not fetched or checked. Fenced code and inline-code examples are ignored.

This is deliberately not a full Markdown parser. Reference/shortcut links, HTML links, angle-wrapped destinations, percent-encoded or whitespace-containing destinations, titles, query strings, and nested link syntax are unsupported and fail closed. Use plain inline links. Bare paths in code spans are instructions, not checked navigation.

Root-only ignored tool scratch directories and generated caches are excluded; project source/docs are never excluded merely because their names resemble tool scratch. Checks do not fetch external resources or prove publication rights. See [project gate syntax](PROJECTS.md) for manifest validation.
