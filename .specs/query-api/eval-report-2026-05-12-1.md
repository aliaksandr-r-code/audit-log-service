# Eval report — .specs/query-api/

**Date**: 2026-05-12
**Checklist**: `.specs/_eval-checklist.MD`
**Scope**: `.specs/query-api/` (requirements.MD, design.MD, tasks.MD)

| # | Item                                       | Verdict | Evidence |
|---|--------------------------------------------|---------|----------|
| 1 | Each AC is testable                        | PASS    | Every AC names a concrete observable outcome (e.g. AC-1.1 "response contains every matching live event and no others", AC-3.1 "Default page size is 50; maximum is 200", AC-3.3 "every matching event exactly once and skips none") — all directly assertable in MockMvc/integration tests. |
| 2 | Tasks has refs and DoD                     | WEAK    | All 8 tasks (T1–T6, T8, T9) have a `**Refs**` line and a `### Definition of Done` checklist; however T3 omits the `**Requirements**` back-ref that T1/T2/T4/T5/T6/T8/T9 all carry — refs to design only, not to requirements.MD. |
| 3 | Pagination strategy is justified           | PASS    | design.MD §4 dedicates a full section to the offset+pageSize choice: total-order rationale on `(occurredAt, id)`, stop condition, explicit caveat under concurrent writes, mitigation via pinning `occurredTo`, and the deferred keyset alternative in §8. |
| 4 | Dependencies between tasks are explicit    | PASS    | Every task carries a `**Depends on**` line (T1/T2/T3: "—"; T4: T2,T3; T5: T4; T6: T5; T8: T6; T9: T6) and tasks.MD opens with an ASCII dependency graph plus a note on the T6→T8/T9 fan-out. |

## Notes

- **Item 2 (WEAK)**: The single inconsistency is T3 — it traces back to design §5.1 but does not cite which requirements line it serves (the implicit owner is CR-1's filter surface for the time-bound predicates). Adding `**Requirements**: requirements.MD CR-1` would bring it in line with the other seven tasks and make the report PASS.
