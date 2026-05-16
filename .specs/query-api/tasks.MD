# Tasks: Read-only Query API for Audit Events

Refs:
- Requirements: `.specs/query-api/requirements.MD`
- Design: `.specs/query-api/design.MD`

Each task is sized to one safe commit / PR and lists its DoD and dependencies. The intended merge order matches the dependency graph; tasks with no dependency on each other can be parallelized.

```
T1 --+
     |
T2 --+--> T4 --> T5 --> T6 --> T8 --> T9
     |
T3 --+
```

Notes:
- T9 (README) depends on T6; it can ship in parallel with T8 once T6 is merged.
- The numbering jumps from T6 to T8 by intent: a planned T7 was folded into T8 during planning. Existing numbers are preserved so cross-references in design/requirements remain valid.

---

## T1 - Flyway V3 migration: composite indexes

**Refs**: design section 5.2, section 7
**Requirements**: requirements.MD CR-4 (perf NFR; the indexes underwrite the SLO)
**Depends on**: -
**Size**: 1 PR

### Description
Add the indexes the query API relies on for index-only scans at the 36.5M-row horizon: composite `(occurred_at, id)` and composite `(aggregate_id, occurred_at, id)`.

### Definition of Done
- [ ] New file `src/main/resources/db/migration/V3__query_api_indexes.sql` exists with the two `CREATE INDEX` statements from design section 5.2.
- [ ] Application boot (Spring Boot's Flyway auto-migrate) succeeds against a fresh Postgres - verified by the existing TestContainers integration suite, which spins up a clean DB and runs all migrations on startup. (No Flyway Gradle plugin is configured in `build.gradle`; migrations run via `spring-boot-starter-data-jpa` + `flyway-core` on application start.)
- [ ] The existing TestContainers-backed integration test (`AuditEventControllerIntegrationTest`) still passes after migration (regression check that V3 does not break the legacy schema).
- [ ] `EXPLAIN` on `SELECT ... WHERE aggregate_id = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id` reports `Index Scan using idx_audit_events_aggregate_occurred_at_id` (verified manually against a seeded local DB; not asserted in code).

---

## T2 - Domain query object, sort enum, and result type

**Refs**: design section 3.1, section 3.2, section 4
**Requirements**: requirements.MD CR-1 (filter surface), CR-2 (pagination/ordering)
**Depends on**: -
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

## T3 - Extend `AuditEventSpecifications` with two time-bound predicates

**Refs**: design section 5.1
**Depends on**: -
**Size**: 1 PR

### Description
Add the two JPA Specifications used by the query path that do not already exist. The equality specs are reused unchanged.

### Definition of Done
- [ ] `occurredAtAtLeast(Instant from)` - returns null when `from` is null, else `cb.greaterThanOrEqualTo(root.get("occurredAt"), from)`.
- [ ] `occurredAtBefore(Instant to)` - returns null when `to` is null, else `cb.lessThan(root.get("occurredAt"), to)`.
- [ ] Existing `hasAction(String)`, `hasActor(String)`, `hasAggregateId(String)`, and `isNotArchived()` are reused unchanged; this task does not modify them.
- [ ] Unit tests for each of the two new specifications cover the null-input branch and a positive-match assertion via a TestContainers Postgres (or via `Specification.toPredicate` with a stubbed root, whichever the suite already prefers).
- [ ] Existing tests for the reused specs still pass.

---

## T4 - Persistence adapter: `query(QueryAuditEventsQuery)`

**Refs**: design section 5
**Requirements**: requirements.MD AC-1.1, AC-2.1, AC-2.3, AC-3.3
**Depends on**: T2, T3
**Size**: 1 PR

### Description
Add a single new method to `AuditEventPersistenceAdapter` that composes the Specifications, applies arbitrary-row offset pagination plus ordering, and returns a `QueryAuditEventsResult` (mapping entities to domain).

### Definition of Done
- [ ] Method `query(QueryAuditEventsQuery): QueryAuditEventsResult` exists on `AuditEventPersistenceAdapter`.
- [ ] Always applies `isNotArchived()`; always applies `Sort.by(direction, "occurredAt").and(Sort.by(direction, "id"))`.
- [ ] Pagination uses **arbitrary row offset** (not page-number arithmetic): `PageRequest.of(offset / pageSize, pageSize, sort)` is NOT acceptable because `PageRequest` takes a page number - `offset=75, pageSize=50` would resolve to rows 50-99 instead of 75-124. Use one of: (a) a custom `Pageable` that overrides `getOffset()` to return the raw offset and `getPageNumber()` to return `offset / pageSize`, paired with `findAll(spec, pageable)`; (b) `EntityManager` with `setFirstResult(offset)` / `setMaxResults(pageSize)` + a separate `repository.count(spec)` call. Chosen approach noted in a one-line code comment only if non-obvious.
- [ ] Returns `totalCount` matching the snapshot of rows the filters select (from `Page.getTotalElements()` or an explicit `count(spec)` call, depending on chosen approach).
- [ ] Existing legacy `search(...)` method is unchanged; its tests still pass.
- [ ] Integration test (`@Tag("integration")`, TestContainers) seeds ~30 events and exercises: filter by `aggregateId`, time-range filter, descending vs ascending sort, deterministic tie-break on identical `occurredAt`, archived rows excluded.
- [ ] Test fixtures with >2 fields live in `src/test/resources/query-api/` per project rule.

---

## T5 - Application service: `AuditEventService.query` with validation

**Refs**: design section 3, section 4
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

## T6 - Controller endpoint `GET /api/v1/audit-events/query`

**Refs**: design section 3.1, section 3.2, section 3.3
**Requirements**: requirements.MD CR-1, CR-2, CR-3
**Depends on**: T5
**Size**: 1 PR

### Description
Expose the new endpoint on `AuditEventController` with request-param bindings and a wrapper response DTO. Map `IllegalArgumentException` to 400.

### Definition of Done
- [ ] New `@GetMapping("/query")` handler in `AuditEventController` accepts the params from design section 3.1-section 3.2 with `@DateTimeFormat(iso = ISO.DATE_TIME)` on the Instant params.
- [ ] `aggregateId`, `action`, and `actor` are bound as plain `String`, all optional.
- [ ] Defaults: `offset = 0`, `pageSize = 50`, `sort = desc`. Omitted filters bind to `null` and drop out of the composed predicate.
- [ ] Response DTO `QueryResponse(List<AuditEventResponse> items, int offset, int pageSize, long totalCount)` exists.
- [ ] Existing `search`, `record`, `archive` handlers are byte-for-byte unchanged.
- [ ] `@ExceptionHandler(IllegalArgumentException.class)` returns 400 with a small problem JSON `{ "message": "..." }`.
- [ ] MockMvc / `@WebMvcTest` slice covers: ISO-8601 timestamp binding, default values, 400 mapping for invalid `occurredFrom`/`occurredTo`/`offset`/`pageSize`, response shape.

---

## T8 - End-to-end integration test suite for the query endpoint

**Refs**: design section 3, section 4, section 7
**Requirements**: requirements.MD US-1, US-2, US-3, CR-4 (perf NFR)
**Depends on**: T6
**Size**: 1 PR

### Description
A dedicated integration test class exercising the full HTTP-to-Postgres path under `@Tag("integration")`, asserting each persona's user story round-trips correctly and that the perf NFR is honored.

### Definition of Done
- [ ] `AuditEventQueryIntegrationTest` exists, `@Tag("integration")`, runs under the `integrationTest` Gradle task, uses TestContainers Postgres.
- [ ] Compliance scenario: seed events for actor X across a 30-day window; query with `actor=X`, `occurredFrom`, `occurredTo`, `action=DELETE_RECORD`; assert exact set returned.
- [ ] SRE timeline scenario: seed events on `aggregate_id=R` interleaved with other aggregates; query with `aggregateId=R`, `sort=asc`, time window covering the incident; assert ordering by `(occurredAt asc, id asc)` and that no other aggregate's events leak in.
- [ ] Security analyst pagination scenario: seed ~150 events; walk pages with `pageSize=50` from offset 0 -> 100 -> 150; assert `totalCount=150`, no row appears twice across pages, no row is missed.
- [ ] Concurrent-write scenario: between page 1 and page 2, insert one new event with `occurredAt` inside the already-paged window; assert that the page-2 response (with `occurredTo` pinned at the start of the test) still returns the original ordering and totalCount - codifying the design section 4 mitigation.
- [ ] Archived-row exclusion: archived events never appear in the response, regardless of filters.
- [ ] Performance check: with ~1,000 seeded events, 20 warm sequential calls against a 500-row page complete with **p95 <= 300 ms**; the assertion runs under the `integrationTest` Gradle task and fails the build on regression. (Refs: requirements.MD CR-4, design section 7.)
- [ ] Test fixtures live under `src/test/resources/query-api/` as JSON.

---

## T9 - Documentation: README API section

**Refs**: design section 3
**Requirements**: requirements.MD CR-6 (backwards compatibility)
**Depends on**: T6
**Size**: 1 PR

### Description
Document the new endpoint alongside the existing API in the project README.

### Definition of Done
- [ ] README API section adds `GET /api/v1/audit-events/query` with the param table (`aggregateId`, `action`, `actor`, `occurredFrom`, `occurredTo`, `offset`, `pageSize`, `sort`), the response shape, and a worked-example `curl` for each persona's typical query.
- [ ] Documents that archived rows are not returned by this endpoint.
- [ ] Documents the offset-pagination caveat under concurrent writes (design section 4) and the `occurredTo`-pinning mitigation, in one short paragraph aimed at API consumers.
- [ ] Spotless / Markdown lint (whatever is configured) passes.

---

# Follow-up tasks: multi-actor query delta

The original T1-T9 task set is already implemented. The tasks below are
incremental changes only; do not reopen or rewrite the completed tasks unless a
new task explicitly names the existing code it must adapt.

```
T10 --+
      +--> T12 --> T14
T11 --+      ^
             |
T13 ---------+
  +--> T15
```

Notes for follow-up tasks:
- T10-T15 are the only tasks for the multi-actor delta. T1-T9 remain frozen
  because they are already implemented.
- Where T1-T9 contain older baseline wording, the follow-up task wins only for
  the multi-actor behavior described in requirements.MD AC-1.1a, AC-1.1b,
  AC-3.6, CR-3.1, and CR-4.3.
- Performance verification for the delta follows design section 7: p95 <= 300 ms for
  both the public maximum 200-row endpoint page and the internal 500-row CR-4
  stress benchmark.
- The frozen T8 phrase "500-row page" is historical wording for the internal
  CR-4 stress benchmark below controller validation; it does not permit
  `pageSize=500` through the public HTTP endpoint.

## T10 - Flyway V4 migration: case-insensitive actor index

**Refs**: design section 5.2, section 7
**Requirements**: requirements.MD CR-4.3
**Depends on**: T1-T9 implemented
**Size**: 1 PR

### Description
Add the new composite expression index required by the case-insensitive
multi-actor query path. This is a new migration only; leave the already
implemented V3 query indexes unchanged.

### Definition of Done
- [ ] New file `src/main/resources/db/migration/V4__query_api_actor_lower_occurred_at_id_index.sql` exists.
- [ ] The migration creates `idx_audit_events_lower_actor_occurred_at_id` on `audit_events (lower(actor), occurred_at, id)`.
- [ ] Application boot against a fresh TestContainers Postgres runs all migrations V1-V4 successfully.
- [ ] Existing query API tests still pass after the new migration.
- [ ] Manual `EXPLAIN` against a seeded local Postgres confirms the query shape `lower(actor) IN (?, ?) AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id` uses `idx_audit_events_lower_actor_occurred_at_id` or an index bitmap plan including that index.

---

## T11 - Application service: normalize and validate actor lists

**Refs**: design section 3.1, section 3.3, section 5.1
**Requirements**: requirements.MD AC-1.1a, AC-1.1b, CR-1, CR-3
**Depends on**: T1-T9 implemented
**Size**: 1 PR

### Description
Extend the already implemented `AuditEventService.query` path so the service,
not the controller, owns actor-list parsing, validation, de-duplication, and
case normalization.

### Definition of Done
- [ ] `AuditEventService.query` splits raw `actor` on comma, trims surrounding whitespace, lower-cases values with `Locale.ROOT`, de-duplicates values, and preserves deterministic actor order.
- [ ] `actor=a1, A1, a2` produces normalized actors `["a1", "a2"]`.
- [ ] `actor=a1,,a3`, `actor=`, and any other empty-token case throw `IllegalArgumentException` with a message naming `actor`.
- [ ] More than 10 distinct actors after de-duplication throws `IllegalArgumentException` with a message naming `actor`.
- [ ] Existing validation for `occurredFrom`, `occurredTo`, `offset`, and `pageSize` remains unchanged.
- [ ] Service-layer tests cover trimming, case normalization, duplicate removal, empty-token rejection, max-10 enforcement, and happy-path delegation with normalized actor values.

---

## T12 - Persistence: case-insensitive OR actor predicate

**Refs**: design section 5.1, section 5.2
**Requirements**: requirements.MD AC-1.1a, AC-1.1b, AC-3.6, CR-1
**Depends on**: T10, T11
**Size**: 1 PR

### Description
Extend the query persistence path to apply a case-insensitive OR-combined actor
predicate using the normalized actors produced by the service.

### Definition of Done
- [ ] `AuditEventSpecifications` exposes `hasAnyActorIgnoreCase(Collection<String> actors)`.
- [ ] The new actor specification returns null for null/empty normalized actor collections.
- [ ] The new actor specification compares `lower(root.get("actor"))` with the normalized actor values using an `IN` predicate.
- [ ] The query persistence path uses `hasAnyActorIgnoreCase(...)` for the query endpoint without changing the legacy exact `hasActor(String)` behavior used elsewhere.
- [ ] Actor predicate tests prove case-insensitive matching and OR semantics across at least two actor values.
- [ ] Persistence integration tests prove multi-actor filtering remains AND-combined with `action`, `aggregateId`, time-window predicates, and `isNotArchived()`.

---

## T13 - Controller contract: actor list passthrough and 400 mapping

**Refs**: design section 3.1, section 3.3
**Requirements**: requirements.MD CR-1, CR-3
**Depends on**: T11
**Size**: 1 PR

### Description
Keep the controller as an HTTP adapter: bind the raw `actor` query parameter as
an optional `String`, pass it to the service unchanged, and surface service
validation failures as `400` problem responses.

### Definition of Done
- [ ] `GET /api/v1/audit-events/query?actor=a1,%20a2` passes the raw actor string to `AuditEventService.query`.
- [ ] No actor-list parsing, lower-casing, or de-duplication is implemented in the controller.
- [ ] Existing `IllegalArgumentException` to `400` mapping covers actor validation failures.
- [ ] Web slice tests cover raw comma-separated actor binding with whitespace.
- [ ] Web slice tests cover 400 responses for empty actor token and more than 10 distinct actors.
- [ ] Existing controller tests for timestamps, defaults, pagination validation, and response shape remain green.

---

## T14 - Integration tests: multi-actor query behavior

**Refs**: design section 3, section 4, section 5.1, section 5.2, section 7
**Requirements**: requirements.MD US-1, US-3, CR-1, CR-3, CR-4, CR-4.3
**Depends on**: T12, T13
**Size**: 1 PR

### Description
Add end-to-end TestContainers coverage for the new actor-list behavior through
the real HTTP endpoint and Postgres database, including the index-backed
multi-actor performance path from design section 7. Public endpoint assertions use
the public maximum 200-row page; the retained CR-4 benchmark exercises the same
query path as an internal 500-row stress read below controller validation.

### Definition of Done
- [ ] Compliance scenario seeds matching and non-matching actors, then queries `actor=X, y, X` with `action` and a half-open time window; the response contains only matching live events for actors X/Y, with duplicate actor input not changing the result.
- [ ] Case-insensitive scenario proves stored actor casing does not affect matching.
- [ ] Security analyst pagination scenario walks multiple pages with a multi-actor filter and asserts `totalCount`, no duplicates across pages, and no missed rows.
- [ ] Raw-offset scenario with a multi-actor filter proves an offset that is not divisible by `pageSize` returns the exact expected row window, matching design section 5.1 and guarding against page-number arithmetic regressions.
- [ ] Validation scenario asserts HTTP 400 for `actor=a1,,a3`.
- [ ] Validation scenario asserts HTTP 400 for 11 distinct actors and proves duplicates do not count toward the 10-actor limit.
- [ ] Public performance scenario seeds a representative dataset and runs N warm sequential multi-actor HTTP queries against a 200-row page, asserting p95 <= 300 ms.
- [ ] Internal CR-4 stress scenario runs N warm sequential multi-actor query-path reads against a 500-row page below controller validation, asserting p95 <= 300 ms and proving the index plan has headroom above the public cap.
- [ ] Test fixtures with more than two fields live under `src/test/resources/query-api/`.
- [ ] New integration tests are green under the project integration-test task.

---

## T15 - README: document multi-actor query semantics

**Refs**: design section 3.1, section 3.3
**Requirements**: requirements.MD CR-1, CR-3, CR-6
**Depends on**: T13
**Size**: 1 PR

### Description
Update the existing query API documentation for the new actor-list contract.

### Definition of Done
- [ ] README query endpoint parameter table documents `actor` as a comma-separated list.
- [ ] README states that actor values are trimmed, de-duplicated, matched case-insensitively, OR-combined within the actor filter, and AND-combined with all other filters.
- [ ] README states the maximum is 10 distinct actors per request after de-duplication.
- [ ] README documents 400 responses for empty actor tokens and more than 10 distinct actors.
- [ ] README includes a compliance-officer example using `actor=a1,a2` with `action`, `occurredFrom`, and `occurredTo`.
- [ ] Spotless / Markdown lint, if configured, passes.
