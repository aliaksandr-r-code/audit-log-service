# Plan: align `.specs/query-api/*.MD` with `prompts/feature.txt`

## Context

`.specs/query-api/prompts/feature.txt` is the source-of-truth prompt for the
read-only query API feature. The three sibling spec files have drifted from
it:

- `requirements.MD` is a thin draft (3 persona one-liners, "Out of scope: Detailed plan", typo `ef`) — no acceptance criteria, no NFRs, no explicit out-of-scope.
- `design.MD` is detailed and largely sound, but adds a Spring Security section (§3.4 per-role auth) that `feature.txt` does not request — `feature.txt` says **"Nothing Else"** beyond a new GET endpoint + Flyway indexes.
- `tasks.MD` mirrors that drift: T7a/T7b are auth tasks not authorized by the prompt.
- Neither design.MD nor tasks.MD surfaces the explicit NFR from `feature.txt`: **p95 ≤ 300ms at 500 rows**.

Goal: bring the three spec files into a consistent, executable shape that matches `feature.txt`'s scope and verification method, and follows AGENTS.MD's requirements rule (user stories with ACs, explicit Out of scope).

## Decisions (made for this plan)

| # | Question                       | Decision                                                                                  | Why                                                                       |
|---|--------------------------------|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| 1 | Auth                           | **Drop from v1.** Move to requirements "Out of scope"; delete §3.4 from design; delete T7a/T7b from tasks. | feature.txt says "Nothing Else" and never mentions auth.                  |
| 2 | p95 ≤ 300ms @ 500 rows         | Surface as a numbered NFR-AC in requirements; reference in design §7; add a perf integration check inside the existing E2E test task. | feature.txt mandates it AND mandates integration-test verification.       |
| 3 | Pagination model               | Keep **offset + totalCount** with `(occurredAt, id)` tiebreaker; document the concurrent-write caveat + `occurredTo`-pinning mitigation. | "Determinism first" is satisfied by the unique tiebreaker within a snapshot; offset is the simpler v1 path consistent with "Nothing Else". |
| 4 | requirements.MD shape          | AGENTS.MD style: stories with numbered ACs, cross-cutting NFRs, explicit Out of scope, short Open questions section. | Matches the project's stated requirements rule.                           |

## Files to modify

### 1. `.specs/query-api/requirements.MD` — full rewrite

Replace the draft with:

- **Problem** — one paragraph: read-only query endpoint, why the existing list endpoint is insufficient.
- **Personas and User Stories** — 3 stories (Compliance, SRE, Security analyst). Each story: short narrative + numbered ACs (AC-1.x, AC-2.x, AC-3.x) covering filter behavior, deterministic ordering, archived-event exclusion, half-open time window, pagination correctness, and `occurredTo`-pinning mitigation.
- **Cross-cutting requirements** —
  - CR-1 filter surface (id, aggregateId, action, actor, occurredFrom, occurredTo, metadata).
  - CR-2 validation/error mapping (400 for malformed inputs).
  - CR-3 **NFR — performance**: p95 ≤ 300ms at 500-row pages, verified by integration test.
  - CR-4 observability (DEBUG logging; no metadata values at INFO+).
  - CR-5 backwards compatibility (legacy `POST/GET/archive` unchanged).
- **Out of scope** — explicit bullets: authentication/authorization (deferred), aggregations, bulk export, full-text metadata, alt sort keys, cursor pagination, separate archived-events endpoint, partitioning.
- **Open questions** — closed: "common code or separate classes" → resolved in design §9.

### 2. `.specs/query-api/design.MD` — surgical edits

- **§3.3 Errors** — keep 400 mapping; remove the 401 and 403 lines.
- **§3.4 Authorization — per-role** — **delete entire subsection**. Move a one-liner to §8 (deferred): *"Authentication / authorization gating — deferred; v1 endpoint is open relative to existing endpoints."*
- **§6 Defaults table** — drop the "Authorization" row.
- **§7 Scale assumptions** — append one paragraph stating the explicit SLO: p95 ≤ 300ms at 500-row pages on the seeded test dataset, and explain how the §5.2 indexes deliver it (composite covers the dominant access patterns; metadata GIN avoids sequential scans).
- **§8 Out of scope** — add the auth bullet (replacement for §3.4).
- Leave §1, §2, §4, §5.1, §5.2, §9 unchanged. Pagination model stays offset+totalCount.

### 3. `.specs/query-api/tasks.MD` — remove auth tasks, fold perf check into E2E

- **Delete T7a** (Spring Security baseline) and **T7b** (`@PreAuthorize`) entirely.
- **Update the dependency diagram** at the top: T6 → T8 → T9 (no T7 nodes); T1/T2/T3 still feed T4.
- **T6** — remove the line referencing 401/403; keep 400 mapping.
- **T8** — append one new bullet under DoD: *"Performance check: with ~1,000 seeded events, a single page-of-500 query completes within 300ms p95 over 20 sequential calls; assertion uses the test-clock and is tagged for the integration profile."* This satisfies feature.txt's perf NFR via the verification path it already mandates (integration tests).
- **T9** — remove the auth-related README mentions (caveat about roles); keep the offset-pagination caveat.
- Renumber if desired (T7a/T7b removal leaves a gap) — leave gap acceptable; mention in the file header that gaps exist for traceability.

### 4. Copy the plan into the repo

After the three spec edits land:

- Create directory `D:\Java_Projects\audit-log-service\.specs\query-api\plans\` (does not exist yet).
- Copy `C:\Users\aliaksandr\.claude\plans\create-plan-for-specs-query-api-md-federated-lightning.md` to that directory, preserving the filename. Single file, no further changes.

## Critical files (paths)

- `D:\Java_Projects\audit-log-service\.specs\query-api\prompts\feature.txt` — source-of-truth (read-only).
- `D:\Java_Projects\audit-log-service\.specs\query-api\requirements.MD` — full rewrite.
- `D:\Java_Projects\audit-log-service\.specs\query-api\design.MD` — surgical edits to §3.3, §3.4, §6, §7, §8.
- `D:\Java_Projects\audit-log-service\.specs\query-api\tasks.MD` — delete T7a/T7b; update T6, T8, T9 and the dependency diagram.
- `D:\Java_Projects\audit-log-service\.specs\query-api\plans\` — new directory; receive the plan file copy.
- `D:\Java_Projects\audit-log-service\AGENTS.MD` — read-only; the requirements rule (US + AC, explicit Out of scope) is the rubric.

## Things NOT being changed

- Java sources (controller, service, persistence adapter, specifications).
- Flyway migrations (V3 is described in design but not yet on disk; implementation task is in tasks.MD, untouched here beyond T7 removals).
- `build.gradle` — no `spring-boot-starter-security` is added (consistent with the auth-scope decision).
- The existing `requirements.MD` "Out of scope" entry is replaced wholesale; no merge.

## Verification

1. **Spec self-consistency** — re-read the three files end-to-end. Every persona AC in requirements.MD maps to at least one DoD bullet in tasks.MD; every design.MD section is referenced by at least one task.
2. **No auth residue** — `Grep` for `AUDIT_COMPLIANCE`, `AUDIT_SRE`, `AUDIT_SECURITY`, `@PreAuthorize`, `spring-boot-starter-security`, `SecurityFilterChain` across the three .MD files; expect zero hits except in the deferred-work bullet.
3. **feature.txt scope check** — every "Expected change" in feature.txt is covered by a task; nothing in tasks.MD falls outside feature.txt's "Nothing Else" envelope.
4. **AGENTS.MD compliance** — requirements.MD has user stories with numbered ACs and an explicit Out of scope section.
5. **Plan copy** — confirm `D:\Java_Projects\audit-log-service\.specs\query-api\plans\create-plan-for-specs-query-api-md-federated-lightning.md` exists and is byte-identical to the source plan file.
