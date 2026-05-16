# Problem

We need a read-only HTTP API to query audit events from the audit log. The
existing `GET /api/v1/audit-events` exposes only equality filtering on
three fields, returns the entire result set in one shot, and gives no way
to walk a large window. Three roles inside the company need something
richer.

# Personas and User Stories

## US-1 — Compliance officer

*Draft:* Needs to confirm or refute an action during an audit.

> As a compliance officer, I want to confirm or refute that a specific
> action was performed by a specific actor inside an audit window, so
> that I can answer regulator questions with primary evidence.

### Acceptance criteria

- AC-1.1 Given the API is called with an `actor`, a half-open time
  window (`occurredFrom` inclusive, `occurredTo` exclusive), and an
  `action`, the response contains every matching live event and no
  others.
- AC-1.2 Each returned event includes its identifier, actor, action,
  server-assigned timestamp (`occurredAt`), aggregate id, and metadata
  — the same fields the record endpoint accepts.
- AC-1.3 Archived events are never present in the response, regardless
  of the filter combination.
- AC-1.4 The half-open window convention `[from, to)` lets adjacent
  windows tile without gap or overlap.

## US-2 — SRE

*Draft:* Needs to reconstruct the timeline of actions on a resource
during an incident.

> As an on-call SRE, I want to reconstruct the timeline of actions on a
> single resource over the duration of an incident, so that I can
> understand what happened and in which order.

### Acceptance criteria

- AC-2.1 Given the API is called with an `aggregateId` plus a time
  window, the response contains only events for that aggregate.
- AC-2.2 Caller can request ascending order on `occurredAt` so the
  timeline reads from the start of the incident forward.
- AC-2.3 Two events with identical `occurredAt` appear in a stable,
  deterministic order across repeated identical requests, broken by
  the event `id`.
- AC-2.4 `actor` and `action` filters may be combined with
  `aggregateId` to narrow the timeline (AND-combined).

## US-3 — Security analyst

*Draft:* Needs to paginate a large result set without loss or
duplication.

> As a security analyst, I want to walk a large result set page by
> page, so that I can ingest months of activity into my own tooling
> without losing or duplicating events.

### Acceptance criteria

- AC-3.1 The response is paginated by `offset` and `pageSize`. Default
  page size is 50; maximum is 200.
- AC-3.2 The response carries `offset`, `pageSize`, and a snapshot
  `totalCount` of rows matching the filters at query time, so the
  caller can compute whether more pages exist.
- AC-3.3 Given the underlying table is not modified during a
  traversal, walking sequential pages with the same filters returns
  every matching event exactly once and skips none.
- AC-3.4 Given the caller pins the upper time bound (`occurredTo`) to
  a moment at or before the start of the traversal, inserts arriving
  after that moment do not perturb the traversal.
- AC-3.5 The offset-pagination behavior under concurrent inserts and
  archives *without* a pinned upper bound is documented in the API
  documentation so callers can choose the mitigation knowingly. (See
  Out of scope — cursor pagination.)

# Cross-cutting requirements

## CR-1 — Filter surface

The endpoint accepts the following optional filters, AND-combined:

- `aggregateId` — exact match.
- `action` — exact match.
- `actor` — exact match
- `occurredFrom` — timestamp, inclusive lower bound.
- `occurredTo` — timestamp, exclusive upper bound.

Omitted filters are ignored. With every filter omitted, the endpoint
returns every live (non-archived) event subject to pagination.

`id`-based lookup is intentionally **not** part of this surface —
single-event retrieval is covered elsewhere in the audit-events API.
Metadata-based filtering is **out of scope** for v1.

## CR-2 — Pagination and ordering

- CR-2.1 Pagination is offset-based with parameters `offset` (default
  `0`) and `pageSize` (default `50`, max `200`).
- CR-2.2 Default sort is **descending** by `occurredAt`. Caller may
  pass `sort=asc` to flip direction.
- CR-2.3 Tie-break on identical `occurredAt` is by event `id` in the
  same direction, ensuring a deterministic total order across
  repeated requests on the same snapshot.

## CR-3 — Validation and errors

- CR-3.1 `occurredFrom > occurredTo`, `offset < 0`, or `pageSize`
  outside `[1, 200]` → HTTP 400 with a message naming the offending
  field.
- CR-3.2 The endpoint is idempotent and side-effect-free; repeated
  identical requests return the same response (modulo concurrent
  writes).

## CR-4 — Performance (NFR, verifiable)

- CR-4.1 At a 500-row page the endpoint serves p95 latency
  ≤ 300 ms on the integration-test seeded dataset, measured warm
  over N sequential identical requests.
- CR-4.2 The performance target is verified by an integration test
  (TestContainers Postgres) that seeds a representative dataset,
  runs N sequential queries against a 500-row page, and asserts p95
  against the bound. Failure of the assertion fails the build.

## CR-5 — Observability

- CR-5.1 Each query is logged at DEBUG with bound filters,
  pagination, and sort, on a single structured line.

## CR-6 — Backwards compatibility

The existing endpoints — `POST /api/v1/audit-events`,
`GET /api/v1/audit-events`, `POST /api/v1/audit-events/{id}/archive` —
remain unchanged in URL, request shape, and response shape. The query
capability ships at a new path so the legacy contract is not broken.

# Out of scope

- Authentication and authorization. v1 endpoint is open relative to
  the existing endpoints; role gating is deferred.
- Filtering by event `id` (covered by single-event retrieval).
- Filtering by `metadata` (full-text or key/value containment).
- Aggregations — counts, group-by-action, percentiles. This is a
  query API, not an analytics API.
- Bulk export (CSV/NDJSON streaming).
- Ordering by fields other than `occurredAt`.
- Client-supplied event timestamps; `occurredAt` remains server-set.
- Cursor (keyset) pagination — offset + totalCount is v1; cursor is a
  future swap-in if the offset trade-off proves too leaky.
- A separate read endpoint for archived events.
- Time-based table partitioning.

# Open questions

All open questions from the initial draft have been resolved:

- *"Common code or separate classes?"* → resolved: separate endpoint
  (`/audit-events/query`) with its own controller method and DTOs;
  internals (domain model, specifications, persistence adapter) are
  shared with the existing record/list/archive flows. See
  `design.MD §9`.
