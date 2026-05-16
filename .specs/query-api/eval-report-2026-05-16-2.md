# Eval report — .specs/query-api/

**Date**: 2026-05-16
**Checklist**: `.specs/_eval-checklist.MD`
**Scope**: `.specs/query-api/` (requirements.MD, design.MD, tasks.MD)

| # | Item | Verdict | Evidence |
|---|------|---------|----------|
| 1 | Each AC is testable | PASS | requirements.MD AC-3.3: "walking sequential pages...returns every matching event exactly once". |
| 2 | Tasks has refs and DoD | PASS | tasks.MD T4 includes `Refs`, `Requirements`, `Depends on`, and `Definition of Done`. |
| 3 | Pagination strategy is justified | PASS | design.MD §4 explains offset pagination trade-offs and `occurredTo` mitigation. |
| 4 | Dependencies between tasks are expliciit | PASS | tasks.MD graph plus each task's `Depends on` field define dependencies. |
| C | Clarity | FAIL | design.MD §3.1 says `actor` exact match, conflicting with requirements.MD CR-1 comma-separated list. |
| D | Level of detail | WEAK | design.MD §3.3 lists only `400`, while REST API contract lacks explicit `200` response status. |

## Summary

- **PASS**: 4 · **WEAK**: 1 · **FAIL**: 1
- Overall: FAIL

## Notes

- Clarity fails because the required `actor` behavior is inconsistent across files: requirements.MD CR-1 requires a comma-separated OR list, while design.MD §3.1 and §8 narrow `actor` to exact match only. Update design.MD to match CR-1, then add controller/service/task DoD for parsing and testing multi-value actors.
- Level of detail is weak because the REST API contract describes parameters, response body, and `400`, but not the successful status or full status matrix expected by the project design rules. Add an API-status table in design.MD §3 covering `200`, validation `400`, and any intentionally unchanged/default framework statuses.
- The performance target uses a 500-row page in requirements.MD CR-4 and design.MD §7, while pagination caps `pageSize` at 200 in CR-2.1 and design.MD §3.2. Resolve this by changing the NFR page size to 200 or explicitly justifying an internal-only 500-row benchmark that cannot be requested through the public API.
