# Tasks: Read-only Query API for Audit Events

Refs:
- Requirements: `.specs/query-api/requirements.MD`
- Design: `.specs/query-api/design.MD`

Each task is sized to one safe commit / PR and lists its DoD and dependencies. The intended merge order matches the dependency graph; tasks with no dependency on each other can be parallelized.

```
T1 ──┐
     │
T2 ──┼─▶ T4 ──▶ T5 ──▶ T6 ──▶ T8 ──▶ T9
     │
T3 ──┘
```

Notes:
- T9 (README) depends on T6; it can ship in parallel with T8 once T6 is merged.
- The numbering jumps from T6 to T8 by intent: a planned T7 was folded into T8 during planning. Existing numbers are preserved so cross-references in design/requirements remain valid.

---

## T1 — Flyway V3 migration: composite indexes

**Refs**: design §5.2, §7
**Requirements**: requirements.MD CR-4 (perf NFR; the indexes underwrite the SLO)
**Depends on**: —
**Size**: 1 PR

### Description
Add the indexes the query API relies on for index-only scans at the 36.5M-row horizon: composite `(occurred_at, id)` and composite `(aggregate_id, occurred_at, id)`.

### Definition of Done
- [ ] New file `src/main/resources/db/migration/V3__query_api_indexes.sql` exists with the two `CREATE INDEX` statements from design §5.2.
- [ ] Application boot (Spring Boot's Flyway auto-migrate) succeeds against a fresh Postgres — verified by the existing TestContainers integration suite, which spins up a clean DB and runs all migrations on startup. (No Flyway Gradle plugin is configured in `build.gradle`; migrations run via `spring-boot-starter-data-jpa` + `flyway-core` on application start.)
- [ ] The existing TestContainers-backed integration test (`AuditEventControllerIntegrationTest`) still passes after migration (regression check that V3 does not break the legacy schema).
- [ ] `EXPLAIN` on `SELECT … WHERE aggregate_id = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id` reports `Index Scan using idx_audit_events_aggregate_occurred_at_id` (verified manually against a seeded local DB; not asserted in code).

---

## T2 — Domain query object, sort enum, and result type

**Refs**: design §3.1, §3.2, §4
**Requirements**: requirements.MD CR-1 (filter surface), CR-2 (pagination/ordering)
**Depends on**: —
**Size**: 1 PR

### Description
Introduce the application-layer types the service will operate on: a query record carrying every filter and pagination parameter, a sort-direction enum, and a paged result.

### Definition of Done
- [ ] `application/service/QueryAuditEventsQuery` record exists with fields: `aggregateId: String` (null = no filter), `action: String` (null = no filter), `actor: String` (null = no filter), `occurredFrom: Instant` (null = no lower bound), `occurredTo: Instant` (null = no upper bound), `sort: SortDirection`, `offset: int`, `pageSize: int`.
- [ ] `application/service/SortDirection` enum exists with `ASC` and `DESC`.
- [ ] `application/service/QueryAuditEventsResult` record exists with `items: List<AuditEvent>`, `offset: int`, `pageSize: int`, `totalCount: long`.
- [ ] All records are immutable (no setters; `items` defensively copied).
- [ ] Unit tests cover null-handling and field validation invariants for the records.
- [ ] No reference to the new types from existing code yet (this task only adds them).

---

## T3 — Extend `AuditEventSpecifications` with two time-bound predicates

**Refs**: design §5.1
**Depends on**: —
**Size**: 1 PR

### Description
Add the two JPA Specifications used by the query path that do not already exist. The equality specs are reused unchanged.

### Definition of Done
- [ ] `occurredAtAtLeast(Instant from)` — returns null when `from` is null, else `cb.greaterThanOrEqualTo(root.get("occurredAt"), from)`.
- [ ] `occurredAtBefore(Instant to)` — returns null when `to` is null, else `cb.lessThan(root.get("occurredAt"), to)`.
- [ ] Existing `hasAction(String)`, `hasActor(String)`, `hasAggregateId(String)`, and `isNotArchived()` are reused unchanged; this task does not modify them.
- [ ] Unit tests for each of the two new specifications cover the null-input branch and a positive-match assertion via a TestContainers Postgres (or via `Specification.toPredicate` with a stubbed root, whichever the suite already prefers).
- [ ] Existing tests for the reused specs still pass.

---

## T4 — Persistence adapter: `query(QueryAuditEventsQuery)`

**Refs**: design §5
**Requirements**: requirements.MD AC-1.1, AC-2.1, AC-2.3, AC-3.3
**Depends on**: T2, T3
**Size**: 1 PR

### Description
Add a single new method to `AuditEventPersistenceAdapter` that composes the Specifications, applies arbitrary-row offset pagination plus ordering, and returns a `QueryAuditEventsResult` (mapping entities to domain).

### Definition of Done
- [ ] Method `query(QueryAuditEventsQuery): QueryAuditEventsResult` exists on `AuditEventPersistenceAdapter`.
- [ ] Always applies `isNotArchived()`; always applies `Sort.by(direction, "occurredAt").and(Sort.by(direction, "id"))`.
- [ ] Pagination uses **arbitrary row offset** (not page-number arithmetic): `PageRequest.of(offset / pageSize, pageSize, sort)` is NOT acceptable because `PageRequest` takes a page number — `offset=75, pageSize=50` would resolve to rows 50–99 instead of 75–124. Use one of: (a) a custom `Pageable` that overrides `getOffset()` to return the raw offset and `getPageNumber()` to return `offset / pageSize`, paired with `findAll(spec, pageable)`; (b) `EntityManager` with `setFirstResult(offset)` / `setMaxResults(pageSize)` + a separate `repository.count(spec)` call. Chosen approach noted in a one-line code comment only if non-obvious.
- [ ] Returns `totalCount` matching the snapshot of rows the filters select (from `Page.getTotalElements()` or an explicit `count(spec)` call, depending on chosen approach).
- [ ] Existing legacy `search(...)` method is unchanged; its tests still pass.
- [ ] Integration test (`@Tag("integration")`, TestContainers) seeds ~30 events and exercises: filter by `aggregateId`, time-range filter, descending vs ascending sort, deterministic tie-break on identical `occurredAt`, archived rows excluded.
- [ ] Test fixtures with >2 fields live in `src/test/resources/query-api/` per project rule.

---

## T5 — Application service: `AuditEventService.query` with validation

**Refs**: design §3, §4
**Requirements**: requirements.MD CR-3 (validation/errors), CR-5 (observability)
**Depends on**: T4
**Size**: 1 PR

### Description
Add a new service method that performs request-shape validation, delegates to the adapter, and logs at DEBUG.

### Definition of Done
- [ ] Method `query(QueryAuditEventsQuery): QueryAuditEventsResult` exists on `AuditEventService`, annotated `@Transactional(readOnly = true)`.
- [ ] Validates: `occurredFrom <= occurredTo` if both present; `offset >= 0`; `1 <= pageSize <= 200`. Violations throw `IllegalArgumentException` with a message naming the offending field.
- [ ] Logs at DEBUG with all bound parameters, one structured line.
- [ ] Existing `search(...)`, `record(...)`, `archive(...)` are unchanged; their tests still pass.
- [ ] Service-layer unit tests cover each validation branch and a happy-path delegation to a mocked adapter.

---

## T6 — Controller endpoint `GET /api/v1/audit-events/query`

**Refs**: design §3.1, §3.2, §3.3
**Requirements**: requirements.MD CR-1, CR-2, CR-3
**Depends on**: T5
**Size**: 1 PR

### Description
Expose the new endpoint on `AuditEventController` with request-param bindings and a wrapper response DTO. Map `IllegalArgumentException` to 400.

### Definition of Done
- [ ] New `@GetMapping("/query")` handler in `AuditEventController` accepts the params from design §3.1–§3.2 with `@DateTimeFormat(iso = ISO.DATE_TIME)` on the Instant params.
- [ ] `aggregateId`, `action`, and `actor` are bound as plain `String`, all optional.
- [ ] Defaults: `offset = 0`, `pageSize = 50`, `sort = desc`. Omitted filters bind to `null` and drop out of the composed predicate.
- [ ] Response DTO `QueryResponse(List<AuditEventResponse> items, int offset, int pageSize, long totalCount)` exists.
- [ ] Existing `search`, `record`, `archive` handlers are byte-for-byte unchanged.
- [ ] `@ExceptionHandler(IllegalArgumentException.class)` returns 400 with a small problem JSON `{ "message": "..." }`.
- [ ] MockMvc / `@WebMvcTest` slice covers: ISO-8601 timestamp binding, default values, 400 mapping for invalid `occurredFrom`/`occurredTo`/`offset`/`pageSize`, response shape.

---

## T8 — End-to-end integration test suite for the query endpoint

**Refs**: design §3, §4, §7
**Requirements**: requirements.MD US-1, US-2, US-3, CR-4 (perf NFR)
**Depends on**: T6
**Size**: 1 PR

### Description
A dedicated integration test class exercising the full HTTP-to-Postgres path under `@Tag("integration")`, asserting each persona's user story round-trips correctly and that the perf NFR is honored.

### Definition of Done
- [ ] `AuditEventQueryIntegrationTest` exists, `@Tag("integration")`, runs under the `integrationTest` Gradle task, uses TestContainers Postgres.
- [ ] Compliance scenario: seed events for actor X across a 30-day window; query with `actor=X`, `occurredFrom`, `occurredTo`, `action=DELETE_RECORD`; assert exact set returned.
- [ ] SRE timeline scenario: seed events on `aggregate_id=R` interleaved with other aggregates; query with `aggregateId=R`, `sort=asc`, time window covering the incident; assert ordering by `(occurredAt asc, id asc)` and that no other aggregate's events leak in.
- [ ] Security analyst pagination scenario: seed ~150 events; walk pages with `pageSize=50` from offset 0 → 100 → 150; assert `totalCount=150`, no row appears twice across pages, no row is missed.
- [ ] Concurrent-write scenario: between page 1 and page 2, insert one new event with `occurredAt` inside the already-paged window; assert that the page-2 response (with `occurredTo` pinned at the start of the test) still returns the original ordering and totalCount — codifying the design §4 mitigation.
- [ ] Archived-row exclusion: archived events never appear in the response, regardless of filters.
- [ ] Performance check: with ~1,000 seeded events, 20 warm sequential calls against a 500-row page complete with **p95 ≤ 300 ms**; the assertion runs under the `integrationTest` Gradle task and fails the build on regression. (Refs: requirements.MD CR-4, design §7.)
- [ ] Test fixtures live under `src/test/resources/query-api/` as JSON.

---

## T9 — Documentation: README API section

**Refs**: design §3
**Requirements**: requirements.MD CR-6 (backwards compatibility)
**Depends on**: T6
**Size**: 1 PR

### Description
Document the new endpoint alongside the existing API in the project README.

### Definition of Done
- [ ] README API section adds `GET /api/v1/audit-events/query` with the param table (`aggregateId`, `action`, `actor`, `occurredFrom`, `occurredTo`, `offset`, `pageSize`, `sort`), the response shape, and a worked-example `curl` for each persona's typical query.
- [ ] Documents that archived rows are not returned by this endpoint.
- [ ] Documents the offset-pagination caveat under concurrent writes (§4) and the `occurredTo`-pinning mitigation, in one short paragraph aimed at API consumers.
- [ ] Spotless / Markdown lint (whatever is configured) passes.
