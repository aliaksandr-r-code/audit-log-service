# Problem

We need a read-only HTTP API to query audit events from the audit log. The
existing `GET /api/v1/audit-events` exposes only equality filtering on a
fixed handful of fields, returns the entire result set in one shot, and
gives no way to walk a large window. Three roles inside the company need
something richer.

# Personas and User Stories

## US-1 — Compliance officer

*Draft:* Needs to confirm or refute an action during an audit.

> As a compliance officer, I want to confirm or refute that a specific
> action was performed by a specific actor inside an audit window, so that
> I can answer regulator questions with primary evidence.

### Acceptance criteria

- AC-1.1 Given the API is called with an `actor`, a closed time window
  (`occurredFrom`, `occurredTo`), and a single `action`, when matching
  events exist, the response contains every matching event and no others.
- AC-1.2 Given the same call with a multi-value `action` filter (the
  parameter repeated), when events exist for any of the listed actions,
  the response is the union of those events (`IN` semantics).
- AC-1.3 Each returned event includes its identifier, actor, action,
  server-assigned timestamp, aggregate id, and metadata — the same fields
  the record endpoint accepts.
- AC-1.4 Archived events are never present in the response, regardless
  of filter combination.
- AC-1.5 The time window is half-open (`from` inclusive, `to`
  exclusive), so adjacent windows queried by the officer tile without
  gap or overlap.

## US-2 — SRE

*Draft:* Needs to reconstruct the timeline of actions on a resource
during an incident.

> As an on-call SRE, I want to reconstruct the timeline of actions on a
> single resource over the duration of an incident, so that I can
> understand what happened and in which order.

### Acceptance criteria

- AC-2.1 Given the API is called with an `aggregateId` plus a time
  window, the response contains only events for that aggregate.
- AC-2.2 The response can be ordered ascending by occurrence time so
  that the timeline reads from the start of the incident forward.
- AC-2.3 Two events recorded at the same instant appear in a stable,
  deterministic order across repeated identical requests — so
  screenshots and post-mortems do not contradict each other.
- AC-2.4 Filters on `actor`, `action`, and `metadata` may be combined
  with `aggregateId` to narrow the timeline (AND-combined).

## US-3 — Security analyst

*Draft:* Needs to paginate a large result set without loss or
duplication.

> As a security analyst, I want to walk a large result set page by page,
> so that I can ingest months of activity into my own tooling without
> losing or duplicating events.

### Acceptance criteria

- AC-3.1 The response is paginated. Page size is caller-chosen up to a
  service-defined ceiling, with a sensible default when omitted.
- AC-3.2 The response carries enough information to compute whether more
  pages exist — at minimum the total count of rows matching the filters
  at query time, plus the current offset and page size.
- AC-3.3 Given the underlying table is not modified during a traversal,
  walking sequential pages with the same filters returns each matching
  event exactly once and skips none.
- AC-3.4 Given the caller pins the upper time bound (`occurredTo`) to a
  moment at or before the start of the traversal, inserts arriving after
  that moment do not perturb the traversal.
- AC-3.5 The offset-pagination behavior under concurrent inserts and
  archives *without* a pinned upper bound is documented in the API
  documentation so callers can choose the mitigation knowingly. (See
  Out of scope — cursor pagination.)

# Cross-cutting requirements

## CR-1 — Filter surface

The endpoint accepts the following optional filters, AND-combined:

- `id` (repeatable) — match any of the given event ids.
- `aggregateId` — exact match.
- `action` (repeatable) — match any of the given values.
- `actor` (repeatable) — match any of the given values.
- `occurredFrom` / `occurredTo` — half-open time window `[from, to)`.
- `metadata` (repeatable `key=value`) — every pair must be present in
  the event's metadata.

Omitted filters are ignored. All filters omitted means "every live
event," subject to pagination.

## CR-2 — Validation and errors

- CR-2.1 `occurredFrom > occurredTo`, malformed `metadata` entry (no
  `=` separator, empty key, or empty value), `offset < 0`, or
  `pageSize` outside the allowed range → HTTP 400 with a message
  naming the offending field.
- CR-2.2 The endpoint is idempotent and side-effect-free; repeated
  identical requests return the same response (modulo concurrent
  writes).

## CR-3 — Performance (NFR)

- CR-3.1 At a 500-row page size, the endpoint serves p95 latency
  ≤ 300 ms on the integration-test seeded dataset, measured over a
  warm run of repeated identical requests.
- CR-3.2 The performance target is verified by an integration test
  (TestContainers Postgres) that seeds a representative dataset, runs
  N sequential queries, and asserts p95 against the bound.

## CR-4 — Observability

- CR-4.1 Each query is logged at DEBUG with its bound filters,
  pagination, and sort, on a single structured line.
- CR-4.2 Logging does not emit values from `metadata` at INFO or
  higher, to avoid leaking caller-supplied content into shared log
  streams.

## CR-5 — Backwards compatibility

The existing endpoints — `POST /api/v1/audit-events`,
`GET /api/v1/audit-events`, `POST /api/v1/audit-events/{id}/archive` —
remain unchanged in URL, request shape, and response shape. The query
capability ships at a new path so the legacy contract is not broken.

# Out of scope

- Authentication and authorization (role gating, per-persona
  redaction, JWT/OIDC wiring). Deferred; v1 endpoint is open relative
  to the existing endpoints.
- Aggregations (counts, group-by-action, percentiles). This is a
  query API, not an analytics API.
- Bulk export (CSV/NDJSON streaming) for very large windows.
  Pagination covers the up-to-tens-of-thousands case; true exports
  want a different transport.
- Full-text or JSON-path search inside metadata. Multi-pair
  containment is enough for v1.
- Ordering by fields other than `occurredAt`. Direction toggle is in
  v1; alternative sort keys are not.
- Client-supplied event timestamps. `occurredAt` remains server-set.
- Cursor (keyset) pagination — offset + totalCount is v1; cursor is a
  future swap-in if the offset trade-off proves too leaky.
- A separate read endpoint for archived events.
- Time-based table partitioning and other storage-layer
  optimizations; transparent to the API contract when introduced.

# Open questions

All open questions from the initial draft have been resolved in
`design.MD`:

- *"Common code or separate classes?"* → resolved: separate endpoint
  (`/audit-events/query`) with its own controller method and DTOs;
  internals (domain model, specifications, persistence adapter) are
  shared with the existing record/list/archive flows. See
  `design.MD §9`.
