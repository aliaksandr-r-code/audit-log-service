# Plan: implement the read-only Query API end-to-end per `.specs/query-api/tasks.MD`

## Context

`.specs/query-api/{requirements,design,tasks}.MD` are complete and mutually
consistent (tasks.MD was just touched in this session to fix three small
inaccuracies against the codebase: T1 dropped the non-existent
`./gradlew flywayMigrate`, T4 replaced the bogus
`PageRequest.of(offset/pageSize, pageSize)` math with arbitrary-offset
pagination, and a note was added explaining the T7 numbering gap).

No code for the new endpoint exists yet. Today the service ships:

- Migrations: `V1__create_audit_events.sql`, `V2__add_archived_at_to_audit_events.sql` — no V3.
- Specs: `AuditEventSpecifications` is package-private with `hasAggregateId`,
  `hasAction`, `hasActor`, `isNotArchived` — no time-bound specs.
- Adapter: `AuditEventPersistenceAdapter` has `save`, `findById`, `search(String, String, String)` —
  no `query(...)`.
- Service: `AuditEventService` has `record`, `search`, `archive` — no `query(...)`.
- Controller: `AuditEventController` at `/api/v1/audit-events` exposes
  `POST /`, `GET /`, `POST /{id}/archive` — no `GET /query`.
- DTOs: `SearchAuditEventsQuery` is the only application-layer query type.
- Build: `integrationTest` Gradle task runs `@Tag("integration")` tests; the only
  current integration test is `AuditEventControllerIntegrationTest`.

This plan implements T1–T9 (T7 intentionally omitted, see tasks.MD notes block).
Each section maps 1:1 to a task in tasks.MD and is sized to a single safe commit/PR.
Implementation order follows the dependency graph: `T1, T2, T3 → T4 → T5 → T6 → T8, T9`.

## T1 — Flyway V3 migration: composite indexes

**File to create**:
- `src/main/resources/db/migration/V3__query_api_indexes.sql`

**Content** (verbatim from design §5.2):

```sql
CREATE INDEX idx_audit_events_occurred_at_id
    ON audit_events (occurred_at, id);

CREATE INDEX idx_audit_events_aggregate_occurred_at_id
    ON audit_events (aggregate_id, occurred_at, id);
```

**Verification**:
- `./gradlew integrationTest` — the existing `AuditEventControllerIntegrationTest`
  spins a fresh TestContainers Postgres and runs all migrations on Spring Boot
  startup. If V3 is malformed, container boot fails and the suite errors.
- Manual EXPLAIN against a seeded DB (not asserted): the SRE-shape query plans
  via `Index Scan using idx_audit_events_aggregate_occurred_at_id`.

## T2 — Domain query object, sort enum, and result type

**Files to create**, all in
`src/main/java/alaiksandr_r/auditlogservice/application/service/`:

1. `SortDirection.java` — `public enum SortDirection { ASC, DESC }`.
2. `QueryAuditEventsQuery.java` — public record matching `SearchAuditEventsQuery`
   style (1-line record, no validation block — validation lives in the service
   per existing convention):
   ```java
   public record QueryAuditEventsQuery(
       String aggregateId,
       String action,
       String actor,
       Instant occurredFrom,
       Instant occurredTo,
       SortDirection sort,
       int offset,
       int pageSize) {}
   ```
3. `QueryAuditEventsResult.java` — public record; defensively copy `items` in
   the compact constructor (records are immutable, but `List` reference can leak):
   ```java
   public record QueryAuditEventsResult(
       List<AuditEvent> items, int offset, int pageSize, long totalCount) {
     public QueryAuditEventsResult {
       items = List.copyOf(items);
     }
   }
   ```

**Notes**:
- All three types **public** so `AuditEventPersistenceAdapter` (different package)
  can use them, matching `SearchAuditEventsQuery`'s visibility.
- No reference from existing code yet — T4/T5/T6 wire them up.

**Verification**: unit tests under `src/test/java/.../application/service/`
covering record construction, the `items` defensive copy (mutate source list
after construction, assert result unchanged), and enum value count.

## T3 — Extend `AuditEventSpecifications` with two time-bound predicates

**File to edit**:
- `src/main/java/alaiksandr_r/auditlogservice/adapter/out/persistence/AuditEventSpecifications.java`

**Additions** (package-private static, matching existing style):

```java
static Specification<AuditEventEntity> occurredAtAtLeast(Instant from) {
  return (root, query, cb) ->
      from == null ? null : cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
}

static Specification<AuditEventEntity> occurredAtBefore(Instant to) {
  return (root, query, cb) ->
      to == null ? null : cb.lessThan(root.get("occurredAt"), to);
}
```

`hasAction`, `hasActor`, `hasAggregateId`, `isNotArchived` stay byte-for-byte
identical.

**Verification**: unit tests for the two new specs covering the null branch
(returns `null`) and a positive-match branch via a TestContainers-backed
integration test (the project has no pure spec-level unit tests today; tagging
these `@Tag("integration")` is consistent with the existing convention).

## T4 — Persistence adapter: `query(QueryAuditEventsQuery)`

**File to edit**:
- `src/main/java/alaiksandr_r/auditlogservice/adapter/out/persistence/AuditEventPersistenceAdapter.java`

**Method signature**:
```java
public QueryAuditEventsResult query(QueryAuditEventsQuery q) { ... }
```

**Implementation outline**:

1. Compose Specifications (always include `isNotArchived`):
   ```java
   Specification<AuditEventEntity> spec =
       Specification.where(AuditEventSpecifications.isNotArchived())
           .and(AuditEventSpecifications.hasAggregateId(q.aggregateId()))
           .and(AuditEventSpecifications.hasAction(q.action()))
           .and(AuditEventSpecifications.hasActor(q.actor()))
           .and(AuditEventSpecifications.occurredAtAtLeast(q.occurredFrom()))
           .and(AuditEventSpecifications.occurredAtBefore(q.occurredTo()));
   ```

2. Build `Sort` honoring requested direction with `id` as deterministic tie-break:
   ```java
   Sort.Direction d = q.sort() == SortDirection.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
   Sort sort = Sort.by(d, "occurredAt").and(Sort.by(d, "id"));
   ```

3. **Arbitrary-offset pagination** (the key correctness point — `PageRequest.of(page, size)`
   uses a page index, not a row offset). Recommended approach: a tiny inline
   `Pageable` whose `getOffset()` returns the raw offset:
   ```java
   Pageable pageable = new Pageable() {
     @Override public int getPageNumber() { return q.offset() / q.pageSize(); }
     @Override public int getPageSize()   { return q.pageSize(); }
     @Override public long getOffset()    { return q.offset(); }
     @Override public Sort getSort()      { return sort; }
     // unsupported nav methods throw UnsupportedOperationException; we never call them
     @Override public Pageable next() { throw new UnsupportedOperationException(); }
     @Override public Pageable previousOrFirst() { throw new UnsupportedOperationException(); }
     @Override public Pageable first() { throw new UnsupportedOperationException(); }
     @Override public Pageable withPage(int pageNumber) { throw new UnsupportedOperationException(); }
     @Override public boolean hasPrevious() { return q.offset() > 0; }
   };
   Page<AuditEventEntity> page = repository.findAll(spec, pageable);
   ```
   *Alternate path if the inline `Pageable` becomes awkward*: `EntityManager`
   with `setFirstResult(offset)` / `setMaxResults(pageSize)` plus a separate
   `repository.count(spec)` call. Pick one; document in a one-line code comment
   only if non-obvious. Spring Data honors `Pageable.getOffset()` for the
   actual SQL OFFSET, so option A is the cleanest.

4. Map and return:
   ```java
   return new QueryAuditEventsResult(
       page.getContent().stream().map(this::toDomain).toList(),
       q.offset(),
       q.pageSize(),
       page.getTotalElements());
   ```

**Existing `search(...)` is unchanged.**

**Verification**: a new integration test (`@Tag("integration")`, TestContainers)
seeds ~30 events and exercises filter-by-aggregateId, time-range, descending
vs ascending sort, deterministic tie-break on identical `occurredAt`, and
archived-row exclusion. Test fixtures (if >2 fields) live under
`src/test/resources/query-api/` per the project rule.

## T5 — Application service: `AuditEventService.query`

**File to edit**:
- `src/main/java/alaiksandr_r/auditlogservice/application/service/AuditEventService.java`

**Additions**:

```java
@Transactional(readOnly = true)
public QueryAuditEventsResult query(QueryAuditEventsQuery q) {
  validate(q);
  log.debug(
      "Querying audit events: aggregateId={}, action={}, actor={}, "
          + "occurredFrom={}, occurredTo={}, sort={}, offset={}, pageSize={}",
      q.aggregateId(), q.action(), q.actor(),
      q.occurredFrom(), q.occurredTo(), q.sort(), q.offset(), q.pageSize());
  return persistence.query(q);
}

private static void validate(QueryAuditEventsQuery q) {
  if (q.occurredFrom() != null && q.occurredTo() != null
      && q.occurredFrom().isAfter(q.occurredTo())) {
    throw new IllegalArgumentException("occurredFrom must be <= occurredTo");
  }
  if (q.offset() < 0) {
    throw new IllegalArgumentException("offset must be >= 0");
  }
  if (q.pageSize() < 1 || q.pageSize() > 200) {
    throw new IllegalArgumentException("pageSize must be in [1, 200]");
  }
}
```

DEBUG log placeholder style matches the existing `search` log on lines 50–54.

**Existing `record`, `search`, `archive` are unchanged.**

**Verification**: service-layer unit tests cover each validation branch
(naming the offending field in the exception message) and a happy-path
delegation to a mocked `AuditEventPersistenceAdapter`.

## T6 — Controller endpoint `GET /api/v1/audit-events/query`

**File to edit**:
- `src/main/java/alaiksandr_r/auditlogservice/adapter/in/web/AuditEventController.java`

**Additions**:

1. New handler:
   ```java
   @GetMapping("/query")
   public QueryResponse query(
       @RequestParam(required = false) String aggregateId,
       @RequestParam(required = false) String action,
       @RequestParam(required = false) String actor,
       @RequestParam(required = false)
           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredFrom,
       @RequestParam(required = false)
           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredTo,
       @RequestParam(defaultValue = "0")  int offset,
       @RequestParam(defaultValue = "50") int pageSize,
       @RequestParam(defaultValue = "desc") String sort) {
     SortDirection dir = "asc".equalsIgnoreCase(sort) ? SortDirection.ASC : SortDirection.DESC;
     QueryAuditEventsResult result = auditEventService.query(
         new QueryAuditEventsQuery(
             aggregateId, action, actor, occurredFrom, occurredTo, dir, offset, pageSize));
     return new QueryResponse(
         result.items().stream().map(AuditEventResponse::from).toList(),
         result.offset(),
         result.pageSize(),
         result.totalCount());
   }
   ```

2. New response DTO (nested record, alongside existing `AuditEventResponse` /
   `RecordAuditEventRequest`):
   ```java
   public record QueryResponse(
       List<AuditEventResponse> items, int offset, int pageSize, long totalCount) {}
   ```

3. New exception handler (the existing
   `@ExceptionHandler(AuditEventNotFoundException.class)` stays at line 68–71):
   ```java
   @ExceptionHandler(IllegalArgumentException.class)
   public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
     return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
   }
   ```

**Existing `record`, `search`, `archive` handlers are byte-for-byte unchanged.**

**Verification**: `@WebMvcTest`-style slice in
`AuditEventControllerTest` (new) — covers ISO-8601 timestamp binding, default
values for `offset`/`pageSize`/`sort`, 400 mapping for invalid
`occurredFrom`/`occurredTo`/`offset`/`pageSize`, and the response shape.

## T8 — End-to-end integration test suite for the query endpoint

**File to create**:
- `src/test/java/alaiksandr_r/auditlogservice/adapter/in/web/AuditEventQueryIntegrationTest.java`

**Skeleton** — mirror the setup of the existing
`AuditEventControllerIntegrationTest` (lines 33–60 of that file are the
reusable pattern: `@Tag("integration")` + `@SpringBootTest` +
`@AutoConfigureMockMvc` + `@Testcontainers` + `@DynamicPropertySource` for
the JDBC URL + `repository.deleteAll()` in `@BeforeEach`).

**Scenarios** (one `@Test` per item):

1. **Compliance officer (US-1)** — seed events for `actor=X` across a 30-day
   window with assorted actions; query with `actor=X`, `occurredFrom`,
   `occurredTo`, `action=DELETE_RECORD`; assert exact set returned (no extras,
   no misses).
2. **SRE timeline (US-2)** — seed events on `aggregate_id=R` interleaved with
   other aggregates; query with `aggregateId=R`, `sort=asc`, time window
   covering the incident; assert ordering by `(occurredAt asc, id asc)` and
   that no other aggregate leaks in.
3. **Pagination walk (US-3)** — seed ~150 events; walk pages with `pageSize=50`
   from `offset=0 → 50 → 100`; assert `totalCount=150`, no row appears twice
   across pages, no row is missed.
4. **Concurrent-write mitigation** — between page 1 and page 2, insert one new
   event with `occurredAt` *inside the already-paged window*; with `occurredTo`
   pinned at the start of the test, page-2 response is unperturbed (ordering
   and `totalCount` stable). Codifies design §4 mitigation.
5. **Archived-row exclusion** — archived events never appear regardless of
   filter combination.
6. **Performance (CR-4)** — seed ~1,000 events; 20 warm sequential calls
   against a 500-row page; assert p95 ≤ 300 ms. Fails the build on regression.
   Runs under `integrationTest` Gradle task.

**Fixtures**: JSON under `src/test/resources/query-api/` per the project's
test-data rule (current `src/test/resources/requests/*.json` is the pattern).

**Verification**: `./gradlew integrationTest` is green; the perf test fails
the build if the SLO regresses.

## T9 — Documentation: README API section

**File to edit**:
- `README.md` (in the project root — read first to find the existing API
  section, then append).

**Content additions**:

- New subsection for `GET /api/v1/audit-events/query` with a param table:
  `aggregateId`, `action`, `actor`, `occurredFrom`, `occurredTo`, `offset`
  (default 0), `pageSize` (default 50, max 200), `sort` (`desc` default / `asc`).
- The wrapper response shape (`items`, `offset`, `pageSize`, `totalCount`).
- One worked `curl` per persona (compliance, SRE timeline, security analyst
  pagination).
- A short paragraph documenting:
  1. Archived rows are never returned by this endpoint.
  2. The offset-pagination caveat under concurrent writes and the
     `occurredTo`-pinning mitigation (per design §4).
- Existing `POST /`, `GET /`, `POST /{id}/archive` documentation is unchanged.

**Verification**: Spotless / Markdown lint passes (whatever the repo runs in CI).

## Critical files (read-only for orientation, edited during execution)

To edit:
- `src/main/resources/db/migration/V3__query_api_indexes.sql` (new) — T1.
- `src/main/java/alaiksandr_r/auditlogservice/application/service/QueryAuditEventsQuery.java` (new) — T2.
- `src/main/java/alaiksandr_r/auditlogservice/application/service/QueryAuditEventsResult.java` (new) — T2.
- `src/main/java/alaiksandr_r/auditlogservice/application/service/SortDirection.java` (new) — T2.
- `src/main/java/alaiksandr_r/auditlogservice/adapter/out/persistence/AuditEventSpecifications.java` — T3.
- `src/main/java/alaiksandr_r/auditlogservice/adapter/out/persistence/AuditEventPersistenceAdapter.java` — T4.
- `src/main/java/alaiksandr_r/auditlogservice/application/service/AuditEventService.java` — T5.
- `src/main/java/alaiksandr_r/auditlogservice/adapter/in/web/AuditEventController.java` — T6.
- `src/test/java/alaiksandr_r/auditlogservice/adapter/in/web/AuditEventQueryIntegrationTest.java` (new) — T8.
- `src/test/resources/query-api/*.json` (new) — T8 fixtures.
- `README.md` — T9.

For reference (do not modify):
- `.specs/query-api/{requirements,design,tasks}.MD` — authoritative spec.
- `src/main/java/alaiksandr_r/auditlogservice/application/service/SearchAuditEventsQuery.java`
  — style template for T2 records.
- `src/test/java/alaiksandr_r/auditlogservice/adapter/in/web/AuditEventControllerIntegrationTest.java`
  — TestContainers skeleton template for T8.
- `build.gradle` — confirms `integrationTest` task and `@Tag("integration")` wiring.

## Things NOT being changed

- Existing endpoints (`POST /api/v1/audit-events`, `GET /api/v1/audit-events`,
  `POST /api/v1/audit-events/{id}/archive`) — URL, request, response shapes
  all preserved (CR-6).
- Existing `AuditEventPersistenceAdapter.search`, `AuditEventService.search`,
  `AuditEventSpecifications.{hasAggregateId,hasAction,hasActor,isNotArchived}`
  — all byte-for-byte unchanged.
- Flyway V1/V2 migrations.
- `build.gradle` — no new dependencies needed; Spring Data JPA already covers
  `Specification` + `Pageable`.
- No security/auth wiring (out of scope per requirements + design §8).
- No metadata filtering, no `id`-based query filter, no aggregations, no
  bulk export (all out of scope per requirements).

## Verification (end-to-end)

After all PRs land, run from `D:\Java_Projects\audit-log-service`:

1. **Unit + slice tests** — `./gradlew test` is green (Spotless and unit tests).
2. **Integration tests** — `./gradlew integrationTest` is green, including
   the new `AuditEventQueryIntegrationTest` and the perf assertion
   (p95 ≤ 300 ms at 500-row page on 1,000-event dataset).
3. **Smoke against a local Postgres** (docker-compose up):
   - `POST /api/v1/audit-events` to record a few events.
   - `curl 'http://localhost:8080/api/v1/audit-events/query?pageSize=10'` —
     200, wrapped response, `totalCount` present.
   - `curl '…/query?occurredFrom=2026-01-01T00:00:00Z&occurredTo=2026-02-01T00:00:00Z&sort=asc'` —
     time-bounded, ascending ordered by `(occurredAt, id)`.
   - `curl '…/query?pageSize=500'` — accepted; `…/query?pageSize=201` — 400
     with `{"message":"pageSize must be in [1, 200]"}`.
   - `curl '…/query?offset=-1'` — 400.
   - `curl '…/query?occurredFrom=…&occurredTo=…'` with `from > to` — 400.
4. **Regression** — existing `AuditEventControllerIntegrationTest` still
   passes (proves CR-6).
5. **EXPLAIN sanity** (manual, optional) — verify the SRE-shape query plans
   via `idx_audit_events_aggregate_occurred_at_id`.

## Out of scope (not in this plan)

- Cursor (keyset) pagination — deferred per design §8; offset + totalCount
  is v1.
- Authentication / authorization gating — design §8.
- Filtering by event `id`, by `metadata`, multi-value filters, aggregations,
  bulk export, alternate sort keys, time-based partitioning — all design §8.
- T7 — intentionally absent from tasks.MD; folded into T8 during planning.
