# Architecture mapping

The template implements nothing. On activation, preserve each canonical row and explain what is implemented here, reused from a named public revision/path, or not applicable. Only implemented rows appear in manifest layers/planes.

| Area | Status | Rationale |
|---|---|---|
| L4 Experience | not-applicable | No user interface is activated in this template. |
| L3 Decision | not-applicable | No scene/composition decision is activated. |
| L2 Contract | not-applicable | No contract is implemented; activated projects reuse root protocol semantics. |
| L1.5 Orchestration | not-applicable | No validation, scheduling, preemption, state, or resource implementation. |
| L1 Adapter | not-applicable | No device translation implementation. |
| L0 Execution | not-applicable | No deterministic execution or hardware safety implementation. |
| Media | not-applicable | No media delivery; fixtures remain synthetic-only. |
| Transport | not-applicable | No connection or message delivery implementation. |
| Evaluation | not-applicable | No project-specific conformance or performance implementation. |
| Data Governance | not-applicable | No project data is introduced; root publication policy still applies. |

Root [architecture](../../docs/ARCHITECTURE.md) governs every activated project. L3 may remain not-applicable when a user directly supplies intent. Orchestration must not change L2 semantics or replace L0 safety. This mapping grants no release compatibility.
