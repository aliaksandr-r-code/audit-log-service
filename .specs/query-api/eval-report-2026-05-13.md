# Eval report — .specs/query-api/

**Date**: 2026-05-13
**Checklist**: `.specs/_eval-checklist.MD`
**Scope**: `.specs/query-api/` (requirements.MD, design.MD, tasks.MD)

| # | Item                                    | Verdict | Evidence |
|---|-----------------------------------------|---------|----------|
| 1 | Each AC is testable                     | PASS    | Every AC names a concrete observable outcome — AC-3.1 "Default page size is 50; maximum is 200", AC-3.3 "every matching event exactly once and skips none", AC-2.3 "stable, deterministic order...broken by id" — all directly assertable in MockMvc/integration tests. |
| 2 | Tasks has refs and DoD                  | WEAK    | T1/T2/T4/T5/T6/T8/T9 each carry both `**Refs**` (design) and `**Requirements**` back-refs plus a `### Definition of Done` checklist; T3 has `**Refs**: design §5.1` and DoD but omits the `**Requirements**` line carried by all seven other tasks. |
| 3 | Pagination strategy is justified        | PASS    | design.MD §4 dedicates a full section to the offset + pageSize choice: total-order rationale on `(occurredAt, id)`, stop condition, explicit caveat under concurrent writes, `occurredTo`-pinning mitigation, and the deferred keyset alternative cross-referenced in §8. |
| 4 | Dependencies between tasks are explicit | PASS    | Every task has a `**Depends on**` line (T1/T2/T3: "—"; T4: T2,T3; T5: T4; T6: T5; T8: T6; T9: T6) and tasks.MD opens with an ASCII dependency graph plus a note on the T6→T8/T9 fan-out and the T7-folded-into-T8 numbering gap. |
| C | Clarity                                 | PASS    | Concrete locators throughout (§ refs, AC ids, task ids); terms defined where first used ("half-open window `[from, to)`" req §US-1, "live (non-archived) view" design §3.1); abbreviations (NFR, DoD, SRE) standard for the audience and disambiguated by surrounding text. |
| D | Level of detail                         | PASS    | Layering is respected: requirements stays at what/why (filters, AC, NFR bounds), design carries the how with concrete shapes (JSON wrapper §3.2, SQL DDL §5.2, predicate semantics §5.1, scale numbers §7), tasks sized to one PR each with DoD checklists — including T4's spelled-out `PageRequest`-trap warning. |

## Summary

- **PASS**: 5 · **WEAK**: 1 · **FAIL**: 0
- Overall: **PASS** (no FAIL, exactly one WEAK).

## Notes

- **Item 2 (WEAK)** — T3 ("Extend `AuditEventSpecifications`") is the only task without a `**Requirements**` back-ref. Smallest edit to lift to PASS: add `**Requirements**: requirements.MD CR-1 (filter surface — time-bound predicates)` directly below the existing `**Refs**: design §5.1` line, matching the layout of T1/T2/T4–T9.

### Forward-looking suggestions (not checklist items)

1. requirements.MD could spell out the `AC-` / `CR-` / `US-` prefixes once at the top of the file ("Acceptance criteria are numbered `AC-<US>.<n>`; cross-cutting requirements are `CR-<n>`") to remove the small inference step for first-time readers.
2. tasks.MD T4's inline `PageRequest` rationale (≈8 lines inside a DoD bullet) is implementation guidance that would read more naturally as a one-paragraph aside in design.MD §5.1, leaving the DoD bullet to assert the observable outcome only.
3. CR-4.2 names the verifying test method but design §7 also restates the SLO; a single one-line cross-link (`See tasks.MD T8 for the verifying test`) in CR-4.2 would close the loop without duplication.
