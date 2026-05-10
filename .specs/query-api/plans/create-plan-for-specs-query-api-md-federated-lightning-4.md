# Plan: align `.specs/query-api/tasks.MD` with the now-edited `design.MD` and updated AGENTS.MD

## Context

After the previous two passes:

- `requirements.MD` is detailed (US/AC + 7 locked clarifications).
- `design.MD` is updated: auth deleted, `id` filter dropped, `metadata`
  filter dropped, `actionIn`/`actorIn` replaced with reuse of existing
  `hasAction`/`hasActor`, GIN index removed, perf SLO paragraph added
  to §7, §8 deferred list expanded.

`tasks.MD` still mirrors the *old* design: T1 carries the GIN index, T2
has a `metadata` field, T3 lists `actionIn`/`actorIn`/`metadataContainsAll`,
T4 tests metadata containment, T5 validates `metadataValue`, T6 binds
the `metadata` param, T7a/T7b are auth tasks, T8 has metadata test
scenarios, T9 has role caveats — all stale.

AGENTS.MD has gained two relevant rule sections:

- **`## Design rules`** — already honored by the just-edited `design.MD`
  (explicit traceability, justification, no requirements drift). No
  task-level work here.
- **`## Task decomposition rules`** (new) — applies directly:
  1. Each task should have an **obligatory reference to design
     decision** and **optionally to requirement**.
  2. Each task should contain **definition of done**.
  3. Between tasks there should exist **explicit dependencies**.
  4. Each task can be implemented with a **single single-focused,
     observable and easy-reviewable commit**.

Goal of this plan: cascade design changes into `tasks.MD` AND tighten
each task's metadata so it explicitly satisfies the four task-decomposition
rules.

## AGENTS.MD task-decomposition conformance check

| Rule                                                                | Current tasks.MD                              | Action                                                                                              |
|---------------------------------------------------------------------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------|
| 1. Obligatory design ref + optional requirement ref                 | All tasks have **Refs**: design only.         | Keep design ref (already obligatory). **Add optional `Requirements:` line** where a requirement AC/CR clearly motivates the task (e.g., T4–T8). |
| 2. Definition of done                                               | All tasks have a `### Definition of Done` block. | No change — already complies.                                                                       |
| 3. Explicit inter-task dependencies                                 | All tasks have a `**Depends on**:` line + a top-of-file diagram. | Keep. Update the diagram to drop T7a/T7b. Verify dependency lines after the rewrite.                |
| 4. Single-focused, observable, easy-reviewable commit               | All tasks are sized **1 PR**.                 | Keep. Re-validate each task's scope after the cascade — confirm no task balloons or shrinks to nothing post-edit. |

## Diff summary (cascading from updated design.MD + requirements.MD)

| Topic                       | Now (post-design-edit)                                   | tasks.MD currently                                                  | Resolution in this plan                                          |
|-----------------------------|----------------------------------------------------------|---------------------------------------------------------------------|------------------------------------------------------------------|
| Auth                        | Out of scope                                             | T7a, T7b exist; T6/T8 reference 401/403/roles                       | **Delete T7a and T7b**. Strip auth bullets from T6/T8/T9. Update diagram. |
| `id` filter                 | Out of scope                                             | T2 / T6 implicitly carry it via the design's old §3.1               | Confirm no task explicitly references `id` filter; nothing to do beyond design alignment. |
| `metadata` filter           | Out of scope                                             | T2 field, T3 spec, T4 test, T5 validation, T6 binding, T8 scenarios | Drop everywhere.                                                 |
| `action`/`actor` semantics  | Exact match (single value)                               | T2 `actions: List<String>` / `actors: List<String>`; T3 `actionIn`/`actorIn`; T6 `List<String>` binding; T8 multi-value scenario | **Switch to `String`**. Drop `actionIn`/`actorIn`. Reuse existing `hasAction`/`hasActor`. Drop multi-value test scenario. |
| Specs scope                 | Reuse existing `hasAction`/`hasActor`/`hasAggregateId`; add only `occurredAtAtLeast` / `occurredAtBefore`; `isNotArchived` unchanged | T3 lists 5 new specs incl. metadata GIN | T3 shrinks to two new methods + reuse confirmation. |
| Indexes                     | Two composite indexes (no GIN)                           | T1 lists three indexes (incl. GIN)                                  | Drop GIN from T1 SQL + rationale + EXPLAIN bullet stays for the composite. |
| Perf NFR                    | p95 ≤ 300 ms @ 500 rows, integration-tested              | Not surfaced in any task                                            | Add a DoD bullet to T8 that runs N warm requests at 500-row page and asserts p95. |
| Pagination model            | Offset + totalCount                                      | T2/T4 already model offset + totalCount                             | No change.                                                       |

## Per-task edits

### T1 — Flyway V3 migration

- **Title**: change from "composite and GIN indexes" to **"composite indexes"**.
- **Description**: remove the "and GIN over `metadata`" clause.
- **DoD**:
  - Update the migration filename DoD bullet to say *"with the two `CREATE INDEX` statements from design §5.2"* (was three).
  - The EXPLAIN bullet still refers to `idx_audit_events_aggregate_occurred_at_id` — keep.
- **Refs**: keep `design §5.2, §7`.
- **Add**: `Requirements:` line referencing `requirements.MD` CR-4 (perf NFR — these indexes underwrite the SLO).

### T2 — Domain query object, sort enum, and result type

- **DoD field list** for `QueryAuditEventsQuery`:
  - Drop `actions: List<String>` and `actors: List<String>`.
  - Add `action: String` and `actor: String` (null = no filter).
  - Drop `metadata: Map<String, String>`.
  - Final list: `aggregateId: String`, `action: String`, `actor: String`, `occurredFrom: Instant`, `occurredTo: Instant`, `sort: SortDirection`, `offset: int`, `pageSize: int`.
- **Immutability** bullet — drop "defensive copy on list" since no list fields remain.
- **Refs**: keep `design §3.1, §3.2, §4`.
- **Add**: `Requirements:` line referencing CR-1 (filter surface), CR-2 (pagination/ordering).

### T3 — Specifications

- **Title**: keep "Extend `AuditEventSpecifications`" but description must say only **two new methods are added**; the rest are reused.
- **DoD bullets**:
  - **Drop** `actionIn`, `actorIn`, `metadataContainsAll`.
  - Keep `occurredAtAtLeast(Instant from)`.
  - Keep `occurredAtBefore(Instant to)`.
  - **Add** an explicit reuse bullet: *"Existing `hasAction(String)`, `hasActor(String)`, `hasAggregateId(String)`, and `isNotArchived()` are reused unchanged; this task does not modify them."*
  - **Drop** the `actionIn`/`actorIn` single-value/multi-value test bullet.
  - **Drop** the `metadataContainsAll` test bullet.
  - Keep the unit-test bullet for the two new time-bound specs (null branch + positive match).
- **Refs**: keep `design §5.1`.

### T4 — Persistence adapter `query(QueryAuditEventsQuery)`

- **Description**: no change beyond verifying it talks about Specifications composition.
- **DoD**:
  - Integration-test bullet: **drop** "metadata containment". Keep `aggregateId`, time-range, descending vs ascending sort, deterministic tie-break, archived rows excluded.
  - Test fixtures bullet: keep as-is (`src/test/resources/query-api/`).
- **Refs**: keep `design §5`.
- **Add**: `Requirements:` line referencing AC-1.1, AC-2.1, AC-2.3, AC-3.3.

### T5 — Application service `AuditEventService.query`

- **DoD validation bullet**:
  - Drop `metadataValue requires metadataKey (and vice versa)`.
  - Resulting validations: `occurredFrom <= occurredTo` if both present; `offset >= 0`; `1 <= pageSize <= 200`.
- **Logging bullet**: no change.
- **Refs**: keep `design §3, §4`.
- **Add**: `Requirements:` line referencing CR-3 (validation/errors), CR-5 (observability).

### T6 — Controller endpoint

- **DoD**:
  - **Drop** the `action`/`actor` multi-value binding bullet *("bound as `List<String>`")*. Replace with: *"`action` and `actor` bound as plain `String`, optional."*
  - **Drop** the entire `metadata` binding + parser-helper bullet.
  - **Drop** "Repeated `action`, repeated `metadata=k=v`" from the MockMvc test bullet. Replace with "ISO-8601 timestamp binding, default values, response shape."
  - **Drop** "400 mapping for malformed `metadata` entries" — no metadata path.
  - Keep `@DateTimeFormat(...)` on the Instant params; keep defaults `offset=0`, `pageSize=50`, `sort=desc`.
  - Keep `@ExceptionHandler(IllegalArgumentException.class)` → 400.
- **Refs**: change `design §3.1, §3.2, §3.3` (no §3.4 remains in design).
- **Add**: `Requirements:` line referencing CR-1, CR-2, CR-3.

### T7a — Spring Security baseline

- **Delete the entire task** (all 18 lines including header).

### T7b — Apply `@PreAuthorize`

- **Delete the entire task** (all 17 lines including header).

### T8 — End-to-end integration test suite

- **Depends on**: change `T6, T7b` → **`T6`** (T7b removed).
- **DoD**:
  - **Drop** "Metadata containment, single pair", "Metadata containment, multi pair", "Metadata malformed entry" bullets.
  - **Drop** the multi-value re-run inside the Compliance scenario *("Then re-run with `action=DELETE_RECORD&action=UPDATE_RECORD`…")*. Single-value compliance scenario stands.
  - Keep: SRE timeline, Security analyst pagination, Concurrent-write, Archived-row exclusion, JSON test fixtures.
  - **Add** new bullet: *"Performance check: with ~1,000 seeded events, 20 warm sequential calls against a 500-row page complete with p95 ≤ 300 ms; the assertion runs under the `integrationTest` Gradle task and fails the build on regression. (Refs: requirements.MD CR-4, design §7.)"*
- **Refs**: change `requirements (all three personas), design §3, §4` to `design §3, §4, §7`.
- **Add**: `Requirements:` line referencing US-1, US-2, US-3, CR-4.

### T9 — README API section

- **Depends on**: stays at `T6` (was already T6).
- **DoD**:
  - **Drop** any `metadata` example from the worked-example `curl`.
  - **Drop** the role caveat reference.
  - Keep: param table (5 fields), response shape, archived-rows exclusion, offset-pagination caveat + `occurredTo`-pinning mitigation paragraph.
- **Refs**: keep `design §3`.
- **Add**: `Requirements:` line referencing CR-6 (BC).

### Dependency diagram (top of file)

Replace:

```
T1 ──┐
     │
T2 ──┼─▶ T4 ──▶ T5 ──▶ T6 ──▶ T7b ──▶ T8 ──▶ T9
     │                  │       ▲
T3 ──┘                  └───────┤
                                │
T7a ────────────────────────────┘
```

with:

```
T1 ──┐
     │
T2 ──┼─▶ T4 ──▶ T5 ──▶ T6 ──▶ T8 ──▶ T9
     │
T3 ──┘
```

And the explanatory notes block beneath it: drop the T7a / T7b bullets;
either delete the notes block entirely (the diagram speaks for itself)
or replace with: *"T9 (README) depends on T6; it can ship in parallel
with T8 once T6 is merged."*

## Critical files

- `D:\Java_Projects\audit-log-service\.specs\query-api\tasks.MD` — target of all edits.
- `D:\Java_Projects\audit-log-service\.specs\query-api\design.MD` — read-only authority for design-decision references.
- `D:\Java_Projects\audit-log-service\.specs\query-api\requirements.MD` — read-only source for the new optional Requirements refs.
- `D:\Java_Projects\audit-log-service\AGENTS.MD` — read-only; the task-decomposition rules are the rubric.
- `D:\Java_Projects\audit-log-service\src\main\java\alaiksandr_r\auditlogservice\adapter\out\persistence\AuditEventSpecifications.java` — confirms the `hasAction`/`hasActor`/`hasAggregateId`/`isNotArchived` reuse claim in T3.

## Things NOT being changed

- `requirements.MD` (already aligned).
- `design.MD` (already aligned).
- Any Java source under `src/main/java/...` — the actual implementation is what these tasks describe; it lands in subsequent commits per task DoD.
- Flyway migrations on disk — V3 still spec-only; lands during T1 implementation.
- `build.gradle` — no `spring-boot-starter-security` is added.

## Verification

1. **Inter-document consistency** — every `Refs: design §X` in `tasks.MD` resolves to a section that still exists in the updated `design.MD`. Specifically: §3.4 has been deleted from design, so no task may still reference §3.4.
2. **AGENTS.MD task-decomposition rules** — every remaining task has: (a) at least one `Refs: design §X` line, (b) a `### Definition of Done` block, (c) a `**Depends on**:` line, (d) `**Size**: 1 PR`.
3. **Optional requirements traceability** — at least T4, T5, T6, T8 carry an explicit `Requirements:` line.
4. **No auth residue** — `Grep tasks.MD` for `T7a`, `T7b`, `Spring Security`, `@PreAuthorize`, `AUDIT_COMPLIANCE`, `AUDIT_SRE`, `AUDIT_SECURITY`, `401`, `403`, `spring-boot-starter-security`, `SecurityFilterChain` — expect zero hits.
5. **No metadata residue** — `Grep tasks.MD` for `metadata`, `metadataContainsAll`, `actionIn`, `actorIn`, `GIN`, `jsonb_path_ops` — expect zero hits.
6. **Multi-value residue** — `Grep tasks.MD` for `List<String>`, `multi-value`, `repeated` — expect zero hits in T2, T3, T6, T8.
7. **Perf NFR present** — T8 contains "p95" and "300 ms" exactly once.
8. **Diagram correctness** — top-of-file diagram has no T7 nodes.

## Out of scope (for a follow-up plan, if desired)

- Renumbering tasks to close the T7a/T7b gap. The current plan leaves
  the gap as-is (cheaper, preserves PR-history traceability for anyone
  who already referenced "T8" or "T9" in tickets/discussions).
- Adding any new task — for example, a dedicated perf-test task split
  out from T8. The plan folds the perf check into T8's DoD on the
  grounds that the integration-test infrastructure is identical and
  splitting would create a second integration-test PR with overlapping
  setup.
