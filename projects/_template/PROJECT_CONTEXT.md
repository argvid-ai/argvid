# Project context instructions

Status: proposed template, not activated. Root [context](../../PROJECT_CONTEXT.md) and [architecture](../../docs/ARCHITECTURE.md) remain authoritative.

When activated through an approved Ready Issue, describe the public user problem, complete delivery boundary, inclusion basis, accepted decisions, dependencies, input provenance, and known limitations. Before implementation, complete the authoritative project preimplementation supplement in [AGENTS.md](../../AGENTS.md): covered layers, root-module reuse points, new protocol requirements (or none), conformance impact, public dependency completeness with identities and paths and no missing inaccessible requirements, concrete verification entrypoints, and potentially promotable common capability (or none). State a reason for every `none` or `not-applicable` item. Keep all information self-contained and approved for public publication.

Map implemented, reused, and not-applicable areas in [architecture.md](architecture.md). Record source identities for reused public components. Protocol changes use root RFCs and conformance, not local copies. Do not import context from other projects or parent directories.
