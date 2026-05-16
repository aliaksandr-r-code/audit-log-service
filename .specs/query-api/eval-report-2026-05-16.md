# Eval report — .specs/query-api/

**Date**: 2026-05-16
**Checklist**: `.specs/_eval-checklist.MD`
**Scope**: `.specs/query-api/` (requirements.MD, design.MD, tasks.MD)

| # | Item | Verdict | Evidence |
|---|------|---------|----------|
| 1 | Each AC is testable | PASS | `requirements.MD` AC-3.3 states a concrete sequential-page outcome: "exactly once and skips none." |
| 2 | Tasks has refs and DoD | PASS | `tasks.MD` T4 includes `Refs`, `Requirements`, `Depends on`, and `Definition of Done`. |
| 3 | Pagination strategy is justified | PASS | `design.MD` section 4 explains offset pagination, total order, concurrent-write caveats, and mitigation. |
| 4 | Dependencies between tasks are expliciit | PASS | `tasks.MD` dependency graph plus per-task `Depends on` fields make dependencies explicit. |
| C | Clarity | WEAK | `requirements.MD` CR-1 allows comma-separated `actor`, but `design.MD` section 3.1 says exact match. |
| D | Level of detail | WEAK | `requirements.MD` CR-4 requires a 500-row page while CR-2.1 caps `pageSize` at 200. |

## Summary

- **PASS**: 4 · **WEAK**: 2 · **FAIL**: 0
- Overall: WEAK

## Notes

- Row C: The actor filter semantics conflict across the spec: requirements allow comma-separated OR values, while design and T6 describe a plain exact-match string. Decide whether v1 supports multi-actor filtering, then update `design.MD` section 3.1 and the affected tasks to match CR-1.
- Row D: The performance requirement asks for a 500-row page, but the pagination contract rejects `pageSize > 200`. Either lower CR-4 and T8 to a 200-row performance check or explicitly create a separate internal performance scenario that does not violate the public API contract.
