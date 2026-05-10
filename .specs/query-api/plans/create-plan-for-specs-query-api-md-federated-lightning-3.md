# Plan: align `.specs/query-api/design.MD` with the now-detailed `requirements.MD`

## Context

`requirements.MD` was just rewritten with US/AC and 7 locked clarification
answers (per AGENTS.MD's mandatory 5–7 questions rule). `feature.txt`
remains the source-of-truth on scope ("Nothing Else" beyond the new GET
endpoint + Flyway indexes; p95 ≤ 300 ms @ 500 rows; determinism first).

`design.MD` still reflects an earlier scope: it carries an auth section,
an `id` filter, a `metadata` filter (with a JSONB GIN index), multi-value
IN semantics on `action`/`actor`, plus stale architectural wording from a
keyset variant. Bring it into agreement with `requirements.MD` and
`feature.txt`. **No changes to `tasks.MD` in this plan** — that is a
separate downstream pass.

## Diff summary (locked decisions feeding this plan)

| Topic              | Requirements says                                  | Design.MD currently says                       | Resolution                          |
|--------------------|----------------------------------------------------|------------------------------------------------|-------------------------------------|
| Auth               | Out of scope                                       | §3.4 per-role enforcement, §3.3 401/403, §6 row | Delete §3.4; trim §3.3; drop §6 row; add §8 bullet |
| `id` filter        | Out of scope                                       | §3.1 row                                       | Drop §3.1 row; add §8 bullet        |
| `metadata` filter  | Out of scope                                       | §3.1 row, §5.1 metadataContainsAll, §5.2 GIN, §6 row, §7 bullet | Drop everywhere; add §8 bullet      |
| `action`/`actor`   | Exact match (CR-1)                                 | Repeatable IN                                  | Switch §3.1 wording; reuse existing `hasAction`/`hasActor` in §5.1 |
| Pagination         | Offset + totalCount (CR-2.1, AC-3.2)               | Offset + totalCount + stale "cursor/limit+1" wording in §2 | Keep §4; remove stale §2 lines      |
| Sort default       | Descending (CR-2.2)                                | Descending                                     | No change                           |
| Page size          | Default 50 / max 200 (CR-2.1)                      | Default 50 / max 200                           | No change                           |
| Time window        | Half-open `[from, to)` (AC-1.4)                    | Half-open                                      | No change                           |
| Perf NFR           | p95 ≤ 300 ms @ 500 rows, verifiable (CR-4)         | Not surfaced                                   | Append SLO paragraph to §7          |

## Edits to `.specs/query-api/design.MD`

### §2 Architecture (line ~31)

The diagram and surrounding paragraphs are sound, but the bullet
*"Application service owns the query semantics: validation, **cursor
encode/decode, the limit+1 fetch trick**, and result assembly."* is stale
— those phrases belong to a keyset variant, contradict §4 (offset), and
will mislead implementers.

**Edit**: replace that bullet with: *"Application service owns the query
semantics: validation, sort-direction binding, and result assembly."*

### §3.1 Filters table

**Drop these rows**:

- `id`
- `metadata`

**Edit `action` and `actor` rows** to reflect requirements CR-1 (exact
match):

- `action` — exact match
- `actor` — exact match

**Drop the half-open paragraph note about `metadata` shape** (no
metadata filter remains).

Resulting filter set: `aggregateId` (exact), `action` (exact), `actor`
(exact), `occurredFrom` (inclusive), `occurredTo` (exclusive).

The half-open time-window paragraph below the table stays.
The "Archived events are never returned" paragraph stays.
The "client-supplied timestamps out of scope" paragraph stays.

### §3.2 Pagination

No change — already matches CR-2.

### §3.3 Errors

**Delete** the malformed-`metadata` clause from the 400 bullet, and
**delete** the 401 and 403 bullets entirely. Resulting list:

- `occurredFrom > occurredTo`, `offset < 0`, `pageSize` out of range → `400`.

### §3.4 Authorization — per-role

**Delete the entire subsection** (heading, role table, narrative). It is
replaced by a single deferred-work bullet in §8 (see below).

### §4 Pagination model

Body is correct. **Verify and keep** the `(occurredAt, id)` tiebreaker
description (matches CR-2.3 / AC-2.3) and the concurrent-write caveat +
`occurredTo`-pinning mitigation (matches AC-3.4 / AC-3.5).

No change needed.

### §5.1 Specifications

**Delete** these bullets:

- `actionIn(Collection<String> actions)`
- `actorIn(Collection<String> actors)`
- `metadataContainsAll(Map<String, String> pairs)`

**Replace** with a tighter bullet list reusing the existing equality
specs (which already exist in
`src/main/java/.../adapter/out/persistence/AuditEventSpecifications.java`
as `hasAction`, `hasActor`, `hasAggregateId`, `isNotArchived`):

- The existing `hasAction(String)`, `hasActor(String)`, and
  `hasAggregateId(String)` are reused unchanged — each returns null when
  its argument is null.
- New: `occurredAtAtLeast(Instant from)` — null when `from` is null;
  otherwise `cb.greaterThanOrEqualTo(...)`.
- New: `occurredAtBefore(Instant to)` — null when `to` is null;
  otherwise `cb.lessThan(...)`.
- The existing `isNotArchived()` is always applied.

Keep the offset-pagination paragraph at the end of §5.1
(`PageRequest.of(...)`, `findAll(Specification, Pageable)`).

### §5.2 Indexing (Flyway V3)

**Drop the GIN index**. The metadata filter is gone; the GIN over
`metadata` no longer pays for itself.

```sql
CREATE INDEX idx_audit_events_occurred_at_id
    ON audit_events (occurred_at, id);

CREATE INDEX idx_audit_events_aggregate_occurred_at_id
    ON audit_events (aggregate_id, occurred_at, id);
```

Update the rationale paragraph: drop the "GIN on metadata" bullet.
Keep the two composite-index bullets and the closing paragraph about V1
single-column indexes staying in place.

### §6 Defaults and trade-offs (table)

**Drop these rows**:

- `Filter operators` (action/actor are now plain equality; nothing to
  document beyond §3.1)
- `Metadata filter shape`
- `Authorization`

**Keep**: Sort direction, Default page size, Maximum page size, Archived
events visibility.

Adjust the closing sentence ("If any of these is wrong…") accordingly —
remove the `filter operator` example and the `archived visibility`
example wording is fine.

### §7 Scale assumptions

Edit the first sub-bullet about indexes: **drop** the GIN reference. The
revised bullet reads: *"…The composite indexes in §5.2 are sized for
that horizon — `(occurred_at, id)` for the default time-ordered scan and
`(aggregate_id, occurred_at, id)` for the SRE timeline shape."*

**Append a new paragraph** stating the explicit SLO (sourced from
`feature.txt` and codified by `requirements.MD` CR-4):

> **Performance budget.** The endpoint targets p95 ≤ 300 ms at a
> 500-row page on the seeded integration dataset. The §5.2 composite
> indexes deliver this: the SRE timeline shape is served from
> `(aggregate_id, occurred_at, id)` as an index scan with no heap
> visits beyond the page; the time-ordered scan is served from
> `(occurred_at, id)`. Verification is in `tasks.MD` T8 — an
> integration test seeds a representative dataset, runs N sequential
> warm requests against a 500-row page, and asserts p95 against the
> 300 ms bound; build fails on regression.

### §8 Out of scope (deferred)

**Add** these bullets:

- **Authentication / authorization gating.** v1 endpoint is open
  relative to existing endpoints; role gating is deferred. (Replaces
  deleted §3.4.)
- **Filtering by event `id`.** Single-event retrieval is covered
  elsewhere in the audit-events API.
- **Filtering by `metadata`.** Containment, full-text, and JSON-path
  matching are all deferred.

**Drop** the `Per-role data filtering / field redaction` bullet — it is
subsumed by the new auth-deferral bullet.

**Edit** the `Full-text or JSON-path search inside metadata` bullet
since metadata filtering is now wholly deferred — change wording to:
*"Anything beyond exact match on `aggregateId`/`action`/`actor` and the
half-open time window. Multi-value `IN`, `metadata` containment,
full-text, JSON-path matching, and field-prefix search are all
deferred."*

Keep: `Aggregations`, `Bulk export`, `Client-supplied event timestamps`,
`Keyset (cursor) pagination`, `Time-based table partitioning`,
`Ordering by fields other than occurredAt`.

### §9 Open question

No change — the resolution still applies. Optionally update the trailing
sentence "the `AuditEventSpecifications` toolkit (extended, not
duplicated)" to *"reused (with two new time-bound specs added), notapply plan 
duplicated"* to match §5.1.

## Critical files

- `D:\Java_Projects\audit-log-service\.specs\query-api\design.MD` — target of all edits.
- `D:\Java_Projects\audit-log-service\.specs\query-api\requirements.MD` — read-only source of the locked decisions.
- `D:\Java_Projects\audit-log-service\.specs\query-api\prompts\feature.txt` — read-only scope envelope.
- `D:\Java_Projects\audit-log-service\AGENTS.MD` — read-only project rules.
- `D:\Java_Projects\audit-log-service\src\main\java\alaiksandr_r\auditlogservice\adapter\out\persistence\AuditEventSpecifications.java` — confirms `hasAction`/`hasActor`/`hasAggregateId`/`isNotArchived` already exist; §5.1 reuse claim is accurate.

## Things NOT being changed

- `requirements.MD` (already aligned).
- `tasks.MD` (out of scope for this plan; will need a follow-up to drop T7a/T7b, drop GIN/metadata bullets across T1/T3/T4/T6/T8, and add the perf NFR DoD bullet to T8).
- Any Java source under `src/main/java/...`.
- Flyway migrations on disk (V3 is still spec-only; lands during T1 implementation).
- `build.gradle`.

## Verification

1. **Internal consistency** — re-read `design.MD` end-to-end; every reference to `id` filter, `metadata`, `metadataContainsAll`, GIN, `actionIn`, `actorIn`, `AUDIT_COMPLIANCE`, `AUDIT_SRE`, `AUDIT_SECURITY`, `@PreAuthorize`, "cursor encode", "limit+1" is gone (or only present in §8 deferred bullets).
2. **Cross-file consistency** — every CR/AC in `requirements.MD` has a corresponding home in `design.MD` (CR-1 → §3.1, CR-2 → §3.2 + §4, CR-3 → §3.3, CR-4 → §7 SLO paragraph, CR-5 → handled in code/tasks not here, CR-6 → §2 last paragraph).
3. **Grep checks** (read-only, on `design.MD`):
   - `Grep "metadata"` — only matches in §8 (deferred) and the AC-1.2 wording about response shape; no §3.1 / §5 / §6 / §7 hits.
   - `Grep "GIN|jsonb_path_ops|metadataContainsAll|actionIn|actorIn"` — zero hits.
   - `Grep "AUDIT_COMPLIANCE|AUDIT_SRE|AUDIT_SECURITY|@PreAuthorize"` — zero hits.
   - `Grep "401|403"` — zero hits.
   - `Grep "cursor encode|limit\+1"` — zero hits.
4. **Reuse claim** — `Grep` for `hasAction`, `hasActor`, `hasAggregateId`, `isNotArchived` in `AuditEventSpecifications.java`; expect all four present (already verified).
5. **SLO paragraph present** — §7 contains "p95" and "300 ms" once (the new paragraph) and references §5.2 + tasks T8.
