# Design: Read-only Query API for Audit Events

Source: `.specs/query-api/requirements.MD`.

## 1. Problem and goals

A read-only HTTP endpoint that lets three personas extract audit events:

- **Compliance officer** — confirm or refute that a specific action took place during an audit window.
- **SRE** — reconstruct the timeline of actions on a given resource during an incident.
- **Security analyst** — paginate large result sets without loss or duplication.

These three jobs share one underlying need: filter audit events along structured dimensions over a time window, and walk the result set in a stable order. The design treats them as one capability rather than three endpoints.

## 2. Architecture

The service already follows hexagonal ports/adapters. The query API plugs in along the existing seams; no new layers are introduced.

```
HTTP                      Application                  Persistence
─────────────────         ─────────────────            ─────────────────
QueryController     ───▶  AuditEventService.query  ──▶ AuditEventPersistence
(adapter.in.web)          (application.service)        Adapter (adapter.out)
                                                              │
                                                              ▼
                                                       AuditEventSpecifications
                                                       + composite index in DB
```

- **Inbound adapter** translates HTTP into a domain query object and serializes the domain result back to JSON.
- **Application service** owns the query semantics: validation, sort-direction binding, and result assembly.
- **Outbound adapter** composes JPA Specifications and runs the query against Postgres.

The existing `GET /api/v1/audit-events` (legacy list) and `POST /api/v1/audit-events` (record) are left unchanged. The new capability is exposed at a separate path so the legacy contract is not broken.

## 3. Public contract

```
GET /api/v1/audit-events/query
```

### 3.1 Filters

| Param           | Semantics                                                                              |
|-----------------|----------------------------------------------------------------------------------------|
| aggregateId     | exact match                                                                            |
| action          | exact match                                                                            |
| actor           | exact match                                                                            |
| occurredFrom    | timestamp, inclusive lower bound (optional)                                            |
| occurredTo      | timestamp, exclusive upper bound (optional)                                            |

All filters are AND-combined. Any subset may be omitted.

The time window uses the half-open convention `[from, to)` so adjacent windows tile cleanly without overlap or gap.

`occurredAt` is server receipt time, set by the service when the event was recorded. The query API exposes it as-is; client-supplied event timestamps are out of scope of this design.

Archived events are never returned by this endpoint. The query API surfaces only the live (non-archived) view of the audit log; access to archived rows, if needed, belongs to a separate mechanism outside this design.

### 3.2 Pagination

| Param    | Semantics                                                     |
|----------|---------------------------------------------------------------|
| offset   | zero-based row offset, default `0`                            |
| pageSize | page size, default `50`, capped at `200`                      |
| sort     | `desc` (default) or `asc` — direction of `occurredAt` ordering |

The response is wrapped:

```json
{
  "items":      [ /* AuditEventResponse... */ ],
  "offset":     0,
  "pageSize":   50,
  "totalCount": 1234   // total rows matching the filters (snapshot at query time)
}
```

`totalCount` is included so callers can compute the last reachable page without a probe request. It is a snapshot taken at query time and may shift under concurrent writes.

### 3.3 Errors

- `occurredFrom > occurredTo`, `offset < 0`, `pageSize` out of range → `400`.

## 4. Pagination model — offset + pageSize

Pagination is offset-based: each request specifies `offset` and `pageSize`, and the response includes `totalCount` so callers can drive UIs and bounded loops without a probe call.

**Total order.** Postgres returns rows in arbitrary order without an `ORDER BY`, which would silently duplicate or skip rows across pages even on a static table. The query therefore always sorts by the pair `(occurredAt, id)` in the requested direction. `occurredAt` alone is not unique — two events recorded in the same instant would otherwise tie and the engine could pick a different order on each page. `id` is a UUID (not monotonic), but it's unique, which is all a tiebreaker needs to be.

**Stop condition.** Callers stop when `offset + items.length >= totalCount`, or simply when `items` is empty.

**Behavior under concurrent writes — caveat against the security analyst's requirement.** The requirements doc states that the security analyst needs to paginate "without loss or duplication." Offset pagination cannot guarantee this on a write-active table:

- An insert with an `occurredAt` that sorts before the current page boundary shifts every subsequent offset by one — a row visible at the previous page can reappear on the next page.
- An archive shrinks the visible result set (since archived rows are not returned) and can skip a row at the page boundary.

Mitigation surfaced to callers: if a stable traversal is required, the caller should pin an upper bound `occurredTo` to a moment at or before the first request. With `occurredTo` fixed and `occurredAt` immutable, inserts past that bound are excluded, so the only remaining source of drift is concurrent archives — those can shift the offset by removing rows mid-traversal, which the API does not expose a knob to suppress.

This caveat is the principal trade-off of choosing offset over keyset pagination. The design accepts it; the alternative (keyset cursor) is documented in §8 as deferred future work in case the offset trade-off proves too leaky in practice.

## 5. Data access

### 5.1 Specifications

The existing `AuditEventSpecifications` is reused; only the two time-bound specs are new:

- The existing `hasAction(String)`, `hasActor(String)`, and `hasAggregateId(String)` are reused unchanged — each returns null when its argument is null, so omitted filters drop out of the composed predicate.
- New: `occurredAtAtLeast(Instant from)` — null when `from` is null; otherwise `cb.greaterThanOrEqualTo(root.get("occurredAt"), from)`.
- New: `occurredAtBefore(Instant to)` — null when `to` is null; otherwise `cb.lessThan(root.get("occurredAt"), to)`.
- The existing `isNotArchived()` is always applied — archived rows are never returned by this endpoint.

Offset pagination is realized via Spring Data's `PageRequest.of(offset/pageSize, pageSize, sort)`; no cursor predicate is needed. The repository's `findAll(Specification, Pageable)` produces both the page and the total count in a single round-trip.

### 5.2 Indexing (Flyway V3)

```sql
CREATE INDEX idx_audit_events_occurred_at_id
    ON audit_events (occurred_at, id);

CREATE INDEX idx_audit_events_aggregate_occurred_at_id
    ON audit_events (aggregate_id, occurred_at, id);
```

Rationale:

- `(occurred_at, id)` — the natural total-order pair; required for any deep pagination to avoid table scans.
- `(aggregate_id, occurred_at, id)` — the SRE timeline shape (`aggregateId = X AND time range`, ordered by time). The leading equality predicate plus the range/order columns lets Postgres serve this query straight from the index.

The single-column indexes from V1 stay in place; they remain useful for the legacy list endpoint and ad-hoc queries.

## 6. Defaults and trade-offs

The design fixes the following defaults. Each can be revisited; they are picked to keep the v1 surface small and unsurprising.

| Choice                          | Default                                | Why                                                                                  |
|---------------------------------|----------------------------------------|--------------------------------------------------------------------------------------|
| Sort direction                  | descending by default; client flips with `sort=asc` | matches the dominant "latest events first" UX; SRE timelines flip to `asc`         |
| Default page size               | 50                                     | comfortable for browser/CLI inspection                                              |
| Maximum page size               | 200                                    | balances large-export ergonomics against per-request memory and serialization cost  |
| Archived events visibility      | always excluded (no opt-in)            | the query API exposes only the live audit log; archived rows are out of scope       |

If any of these is wrong for the actual users, change is local: page size is controller-level; sort direction is one `Sort.by(...)` call; archived visibility would require lifting the `isNotArchived()` specification out of the always-applied path.

## 7. Scale assumptions

- Ingest rate: **~20,000 events / day** (~230/s peak if uniform; bursty in practice).
- Retention: **5 years**.
- Steady-state row count: ~**36.5M rows** (`20,000 × 365 × 5`).

This shapes the query API in three ways:

- **Indexes are not optional.** At ~36M rows, every filter the API exposes must be index-supported or the query will sequence-scan. The composite indexes in §5.2 are sized for that horizon — `(occurred_at, id)` for the default time-ordered scan and `(aggregate_id, occurred_at, id)` for the SRE timeline shape.
- **Time-bounded queries are the norm.** Most legitimate uses (compliance windows, incident timelines) are bounded by `occurredFrom`/`occurredTo`. Unbounded queries against the full 5-year window are technically supported but expected to be rare; the design does not optimize for them.
- **Offset pagination depth.** At 50 rows per page, walking the entire 36.5M-row table is ~730k requests — deep offsets get progressively slower under offset pagination because Postgres still has to skip every preceding row. In practice callers will be time-bounded (a one-day compliance window is ~20k rows, ~400 pages — fine). Deep paginations across the full retention horizon are not a supported workflow; if that need emerges, the keyset alternative in §8 is the answer.

Partitioning the table monthly or quarterly on `occurred_at` is **not required** at this volume — 36M rows is comfortable for a single Postgres table with the indexes above — but becomes attractive once retention extends or the ingest rate grows. It is listed in §8 as deferred work and is transparent to the API contract when introduced.

**Performance budget.** The endpoint targets **p95 ≤ 300 ms at a 500-row page** on the seeded integration dataset (sourced from `feature.txt`, codified by `requirements.MD` CR-4). The §5.2 composite indexes deliver this: the SRE timeline shape is served from `(aggregate_id, occurred_at, id)` as an index scan with no heap visits beyond the page; the time-ordered scan is served from `(occurred_at, id)`. Verification lives in `tasks.MD` T8 — an integration test seeds a representative dataset, runs N sequential warm requests against a 500-row page, and asserts p95 against the 300 ms bound; the build fails on regression.

## 8. Out of scope (deferred)

Not addressed by this design — flagged so they are recognized as future work, not gaps:

- **Authentication / authorization gating.** v1 endpoint is open relative to existing endpoints; role gating is deferred. (Replaces the deleted §3.4.)
- **Filtering by event `id`.** Single-event retrieval is covered elsewhere in the audit-events API.
- **Filtering by `metadata`.** Containment, full-text, and JSON-path matching are all deferred.
- **Anything beyond exact match on `aggregateId`/`action`/`actor` and the half-open time window.** Multi-value `IN`, `metadata` containment, full-text, JSON-path matching, and field-prefix search are all deferred.
- **Ordering by fields other than `occurredAt`.** Direction toggle is in v1; alternative sort keys are not.
- **Aggregations** (counts, group-by-action). This is a query API, not an analytics API.
- **Bulk export** (CSV/NDJSON streaming) for very large windows. Pagination covers the up-to-tens-of-thousands case; true exports want a different transport.
- **Client-supplied event timestamps.** `occurredAt` remains server-set.
- **Keyset (cursor) pagination.** Available as a future swap-in if the offset trade-off documented in §4 proves too leaky under concurrent writes.
- **Time-based table partitioning.** A storage-layer optimization that becomes attractive given the 5-year retention; transparent to the API contract.

## 9. Open question from `requirements.MD`

> *"Not clear whether should be implemented as common code or separate classes."*

Resolved as: **separate endpoint, shared internals.** The new path `/audit-events/query` is its own controller method with its own request/response DTOs, so its contract is free to evolve. Underneath, it shares the `AuditEvent` domain record, the `AuditEventSpecifications` toolkit (reused — only two new time-bound specs added, not duplicated), and the persistence adapter with the existing record/list/archive flows. Filter logic lives in one place; only the HTTP shape is endpoint-specific.
