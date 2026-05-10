# Plan v3: align `.specs/query-api/*.MD` with `prompts/feature.txt` under updated AGENTS.MD

## Context

Inputs:

1. `.specs/query-api/prompts/feature.txt` — read-only API, p95 ≤ 300 ms
   at 500 rows, GET endpoint + Flyway indexes only ("Nothing Else"),
   determinism first, integration-test verification, hexagonal/adapter
   architecture.
2. **Updated** `AGENTS.MD` — `## Requirements rules` was simplified to:
   - "Requirements for feature should contain User Stories with
     Accepted Criteria"
   - **"Always ask up to 5-7 queries before generate User Story and
     Acceptance Criteria"** — 5–7 clarification questions are now
     **mandatory**, not optional.
   - The previous mandate "Section 'Out of scope' is written
     explicitly" is gone.

State of the three spec files at the start of this plan:

- `requirements.MD` — **detailed** in this iteration with US/AC after
  Step 0 clarifications were answered.
- `design.MD` — detailed; carries an auth section (§3.4), a metadata
  filter (§3.1, §5.1, §5.2), and an `id` filter (§3.1) that the
  locked-in scope drops.
- `tasks.MD` — mirrors design; T7a/T7b auth tasks, metadata-related
  bullets in T1/T3/T4/T6/T8, no perf-NFR task.

Goal: every file consistent with the locked answers below + the
"Nothing Else" envelope of `feature.txt`.

## Step 0 — Mandatory clarifications (done; 7 of 7)

Per the new AGENTS.MD rule, asked the user 7 questions before drafting
US/AC. Locked answers:

| # | Question                       | Answer                                                          |
|---|--------------------------------|------------------------------------------------------------------|
| 1 | Pagination model               | **Offset + totalCount** (with `(occurredAt, id)` tiebreaker).    |
| 2 | Auth scope                     | **Drop from v1**. Out-of-scope bullet only.                      |
| 3 | Filter surface                 | **Core 5** — `aggregateId`, `action`, `actor`, `occurredFrom`, `occurredTo`. **Drop `id` and `metadata`.** |
| 4 | Performance NFR placement      | **Verifiable AC + integration test** (numbered AC + test in T8). |
| 5 | Default sort direction         | **Descending** (latest first).                                   |
| 6 | Time-window convention         | **Half-open `[from, to)`**.                                      |
| 7 | Default / max page size        | **Default 50, max 200.**                                         |

These flow directly into requirements.MD ACs and into the design /
tasks edits below.

## Step 1 — `requirements.MD` (DONE in this iteration)

Detailed file written with:

- Problem paragraph.
- 3 user stories (Compliance, SRE, Security analyst) with numbered ACs
  AC-1.x / AC-2.x / AC-3.x covering filter behavior, deterministic
  ordering, archived exclusion, pagination correctness, and the
  `occurredTo`-pinning mitigation.
- CR-1 filter surface (5 fields).
- CR-2 pagination & ordering (default 50 / max 200; default sort desc;
  `(occurredAt, id)` tiebreak).
- CR-3 validation/errors (400 only — no auth codes).
- CR-4 performance NFR — verifiable AC + integration test asserts p95
  ≤ 300 ms at 500-row page.
- CR-5 observability (DEBUG log).
- CR-6 backwards compatibility (legacy endpoints unchanged).
- Out of scope (discretionary under new AGENTS.MD; retained for
  clarity) — auth, `id` filter, metadata filter, aggregations, bulk
  export, alt sort keys, client timestamps, cursor pagination, archived
  endpoint, partitioning.
- Open questions — closed.

## Step 2 — Surgical edits to `design.MD`

Cascaded from Step 0 answers. Beyond the v2-plan edits, this iteration
also strips metadata and `id` from the contract.

### 2a. §3.1 Filters table

- **Remove** the `id` row.
- **Remove** the `metadata` row.
- Resulting filter surface: `aggregateId`, `action` (repeatable IN),
  `actor` (repeatable IN), `occurredFrom`, `occurredTo`. (The `IN`
  semantics on `action`/`actor` should be re-confirmed against the
  current decision — see Step 5 below; if the user wants strict
  exact-match, drop the repeatable-IN wording too. Default carry-over
  from current design: keep IN.)

### 2b. §3.3 Errors

- Keep 400 mapping; **delete** the 401 and 403 lines (auth dropped).

### 2c. §3.4 Authorization — per-role

- **Delete the entire subsection.** Replace with one bullet in §8:
  *"Authentication / authorization gating — deferred; v1 endpoint is
  open relative to existing endpoints."*

### 2d. §5.1 Specifications

- **Remove** `metadataContainsAll(Map<String,String> pairs)` paragraph.
- Keep `actionIn`, `actorIn`, `occurredAtAtLeast`, `occurredAtBefore`,
  `isNotArchived`.

### 2e. §5.2 Indexing (Flyway V3)

Drop the GIN index since metadata filtering is gone:

```sql
CREATE INDEX idx_audit_events_occurred_at_id
    ON audit_events (occurred_at, id);

CREATE INDEX idx_audit_events_aggregate_occurred_at_id
    ON audit_events (aggregate_id, occurred_at, id);
```

Update the rationale paragraph: remove the GIN bullet; keep the two
composite-index bullets.

### 2f. §6 Defaults table

- **Drop** the "Authorization" row.
- **Drop** the "Metadata filter shape" row.
- Confirm "Default page size" = 50, "Maximum page size" = 200.
- Confirm "Sort direction" default = descending.

### 2g. §7 Scale assumptions

- Append one paragraph stating the explicit SLO: **p95 ≤ 300 ms at
  500-row pages on the seeded test dataset**, and explain how the §5.2
  composite indexes deliver it. Note that the absence of a metadata
  filter further simplifies the query plan (no GIN lookup needed).

### 2h. §8 Out of scope

- **Add** the auth-deferral bullet (replaces deleted §3.4).
- **Add** "Filtering by `id`" and "Filtering by `metadata`" as deferred
  out-of-scope items consistent with the requirements.

### 2i. §9 Open question

- Unchanged.

## Step 3 — Surgical edits to `tasks.MD`

### 3a. T1 — Flyway V3 migration

- Drop the GIN index from the migration body and from the rationale.
- DoD bullets that reference GIN are removed.

### 3b. T2 — Domain query object

- Remove the `metadata: Map<String, String>` field and the corresponding
  null-handling test bullet.
- Confirm the remaining fields: `aggregateId`, `actions`, `actors`,
  `occurredFrom`, `occurredTo`, `sort`, `offset`, `pageSize`.

### 3c. T3 — Specifications

- Remove the `metadataContainsAll` bullet and its test bullet.
- Keep `actionIn`, `actorIn`, `occurredAtAtLeast`, `occurredAtBefore`.

### 3d. T4 — Persistence adapter

- Remove "metadata containment" from the integration-test scenarios bullet.
- Test fixtures path stays under `src/test/resources/query-api/`.

### 3e. T6 — Controller endpoint

- Remove the `metadata` request-param binding and the parser helper bullet.
- Remove the "401 / 403" reference; keep 400.
- MockMvc test bullet: drop metadata cases.

### 3f. T7a, T7b — Spring Security

- **Delete both tasks entirely.**
- Update the dependency diagram at the top of the file:
  `T1 ── T4 ── T5 ── T6 ── T8 ── T9` and `T2/T3 → T4`.

### 3g. T8 — End-to-end integration test suite

- Drop "Metadata containment, single pair" and "multi pair" bullets.
- Drop "Metadata malformed entry" bullet.
- **Add** new perf-NFR bullet under DoD:
  *"Performance check: with ~1,000 seeded events, a single page-of-500
  query completes within 300 ms p95 over 20 sequential warm calls;
  assertion runs under the `integrationTest` Gradle task and fails the
  build on regression."*
- Re-tune scenario coverage: keep compliance / SRE-timeline / pagination
  / archived-exclusion / concurrent-write scenarios; metadata scenarios
  are removed.

### 3h. T9 — README

- Remove the role-caveat mention; `metadata` examples are dropped.
- Keep the offset-pagination caveat and the `occurredTo`-pinning mitigation paragraph.

## Step 4 — Persist this plan

Save this file at:

- `D:\Java_Projects\audit-log-service\.specs\query-api\plans\create-plan-for-specs-query-api-md-federated-lightning-3.md`

(v1 and v2 already exist in that directory; v3 supersedes them.)

## Step 5 — Open follow-ups

The locked answers do not cover one residual question; flag for a
follow-up clarification before executing Steps 2 and 3:

- **`action` / `actor` operator** — current design has them as
  repeatable IN (multi-value). The user picked a "core 5" filter set
  but did not say whether multi-value is in or out. Default carry-over
  in this plan is **keep IN** (single-value is a degenerate case, no
  contract bloat). If the user wants strict equality only, drop the
  repeatable parameter wording from §3.1 of design and from the
  `actionIn`/`actorIn` specs, replacing them with `hasAction`/`hasActor`
  reused from the existing `AuditEventSpecifications`.

## Critical files

- `D:\Java_Projects\audit-log-service\.specs\query-api\prompts\feature.txt` — source-of-truth (read-only).
- `D:\Java_Projects\audit-log-service\AGENTS.MD` — updated requirements rule (read-only).
- `D:\Java_Projects\audit-log-service\.specs\query-api\requirements.MD` — **already detailed in this iteration**.
- `D:\Java_Projects\audit-log-service\.specs\query-api\design.MD` — surgical edits per Step 2.
- `D:\Java_Projects\audit-log-service\.specs\query-api\tasks.MD` — surgical edits per Step 3.

## Things NOT being changed

- Java sources (controller, service, persistence adapter, specifications) — code edits land in tasks T1–T9 implementation, not this planning loop.
- Flyway migrations on disk (V3 still spec-only).
- `build.gradle` — no `spring-boot-starter-security`.

## Verification

1. **AGENTS.MD compliance** — `requirements.MD` has user stories with numbered ACs; the 5–7 question rule is honored (7 asked).
2. **Spec self-consistency** — every persona AC in `requirements.MD` maps to at least one DoD bullet in `tasks.MD`; every `design.MD` section is referenced by at least one task.
3. **No auth residue** — `Grep` for `AUDIT_COMPLIANCE`, `AUDIT_SRE`, `AUDIT_SECURITY`, `@PreAuthorize`, `spring-boot-starter-security`, `SecurityFilterChain` across the three .MD files; expect zero hits except in the deferred-work bullets.
4. **No metadata residue** — `Grep` for `metadata @>`, `GIN`, `metadataContainsAll`, `jsonb_path_ops` across the three .MD files; expect zero hits in the active spec sections (only Out-of-scope mention remains).
5. **No `id`-filter residue** — `Grep` for `?id=`, `IdIn`, `event ids` filtering language outside Out-of-scope.
6. **`feature.txt` scope check** — every "Expected change" in `feature.txt` is covered by a task; nothing in `tasks.MD` falls outside the "Nothing Else" envelope.
7. **Plan persisted** — `D:\Java_Projects\audit-log-service\.specs\query-api\plans\create-plan-for-specs-query-api-md-federated-lightning-3.md` exists.
