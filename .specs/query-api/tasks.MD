# Tasks: Read-only Query API for Audit Events

Refs:
- Requirements: `.specs/query-api/requirements.MD`
- Design: `.specs/query-api/design.MD`

Each task is sized to one safe commit / PR and lists its DoD and dependencies. The intended merge order matches the dependency graph; tasks with no dependency on each other can be parallelized.

```
T1 ──┐
     │
T2 ──┼─▶ T4 ──▶ T5 ──▶ T6 ──▶ T7b ──▶ T8 ──▶ T9
     │                  │       ▲
T3 ──┘                  └───────┤
                                │
T7a ────────────────────────────┘
```

Notes:
- T7a (Spring Security baseline) has no dependency on any other task and can land at any time, including before this work begins.
- T7b (apply `@PreAuthorize`) depends on both T6 (endpoint exists) and T7a (security baseline exists).
- T8 depends on T6 and T7b.

---

## T1 — Flyway V3 migration: composite and GIN indexes

**Refs**: design §5.2, §7
**Depends on**: —
**Size**: 1 PR

### Description
Add the indexes the query API relies on for index-only scans at the 36.5M-row horizon: composite `(occurred_at, id)`, composite `(aggregate_id, occurred_at, id)`, and GIN over `metadata`.

### Definition of Done
- [ ] New file `src/main/resources/db/migration/V3__query_api_indexes.sql` exists with the three `CREATE INDEX` statements from design §5.2.
- [ ] `./gradlew flywayMigrate` (or app boot) succeeds against a fresh Postgres.
- [ ] An existing TestContainers-backed integration test still passes after migration (regression check that V3 does not break the legacy schema).
- [ ] `EXPLAIN` on `SELECT … WHERE aggregate_id = ? AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id` reports `Index Scan using idx_audit_events_aggregate_occurred_at_id` (verified manually against a seeded local DB; not asserted in code).

---

## T2 — Domain query object, sort enum, and result type

**Refs**: design §3.1, §3.2, §4
**Depends on**: —
**Size**: 1 PR

### Description
Introduce the application-layer types the service will operate on: a query record carrying every filter and pagination parameter, a sort-direction enum, and a paged result.

### Definition of Done
- [ ] `application/service/QueryAuditEventsQuery` record exists with fields: `aggregateId: String`, `actions: List<String>` (empty means no filter), `actors: List<String>` (empty means no filter), `occurredFrom: Instant`, `occurredTo: Instant`, `metadata: Map<String, String>` (empty means no filter), `sort: SortDirection`, `offset: int`, `pageSize: int`.
- [ ] `application/service/SortDirection` enum exists with `ASC` and `DESC`.
- [ ] `application/service/QueryAuditEventsResult` record exists with `items: List<AuditEvent>`, `offset: int`, `pageSize: int`, `totalCount: long`.
- [ ] All records are immutable (no setters, defensive copy on list).
- [ ] Unit tests cover null-handling and field validation invariants for the records.
- [ ] No reference to the new types from existing code yet (this task only adds them).

---

## T3 — Extend `AuditEventSpecifications` with new predicates

**Refs**: design §5.1
**Depends on**: —
**Size**: 1 PR

### Description
Add the JPA Specifications used by the query path. Existing specs are untouched.

### Definition of Done
- [ ] `actionIn(Collection<String> actions)` — returns null when null/empty, else `cb.in(root.get("action")).value(actions)`.
- [ ] `actorIn(Collection<String> actors)` — analogous to `actionIn`.
- [ ] `occurredAtAtLeast(Instant from)` — returns null when `from` is null, else `cb.greaterThanOrEqualTo(...)`.
- [ ] `occurredAtBefore(Instant to)` — returns null when `to` is null, else `cb.lessThan(...)`.
- [ ] `metadataContainsAll(Map<String, String> pairs)` — returns null when null/empty; otherwise a single Postgres JSONB `@>` containment predicate carrying all key/value pairs in one comparison.
- [ ] Unit tests for each new specification cover the null/empty-input branch and a positive-match assertion via a TestContainers Postgres (or via `Specification.toPredicate` with a stubbed root, whichever the suite already prefers).
- [ ] Unit test for `actionIn`/`actorIn` covers single-value and multi-value cases.
- [ ] Unit test for `metadataContainsAll` covers single-pair and multi-pair cases (asserting one predicate, not N AND-ed predicates).
- [ ] `isNotArchived()` is unchanged; existing tests still pass.

---

## T4 — Persistence adapter: `query(QueryAuditEventsQuery)`

**Refs**: design §5
**Depends on**: T2, T3
**Size**: 1 PR

### Description
Add a single new method to `AuditEventPersistenceAdapter` that composes the Specifications, builds the Pageable from `(offset, pageSize, sort)`, runs `findAll(spec, pageable)`, and returns a `QueryAuditEventsResult` (mapping entities to domain).

### Definition of Done
- [ ] Method `query(QueryAuditEventsQuery): QueryAuditEventsResult` exists on `AuditEventPersistenceAdapter`.
- [ ] Always applies `isNotArchived()`; always applies `Sort.by(direction, "occurredAt").and(Sort.by(direction, "id"))`.
- [ ] `Pageable` is built as `PageRequest.of(offset / pageSize, pageSize, sort)`; if `offset` is not a multiple of `pageSize`, falls back to a manual offset/limit JPQL or `org.springframework.data.domain.OffsetScrollPosition` equivalent — chosen approach documented in a one-line code comment only if non-obvious.
- [ ] Returns `totalCount` from `Page.getTotalElements()`.
- [ ] Existing legacy `search(...)` method is unchanged; its tests still pass.
- [ ] Integration test (`@Tag("integration")`, TestContainers) seeds ~30 events and exercises: filter by `aggregateId`, time-range filter, metadata containment, descending vs ascending sort, deterministic tie-break on identical `occurredAt`, archived rows excluded.
- [ ] Test fixtures with >2 fields live in `src/test/resources/query-api/` per project rule.

---

## T5 — Application service: `AuditEventService.query` with validation

**Refs**: design §3, §4
**Depends on**: T4
**Size**: 1 PR

### Description
Add a new service method that performs request-shape validation, delegates to the adapter, and logs at DEBUG.

### Definition of Done
- [ ] Method `query(QueryAuditEventsQuery): QueryAuditEventsResult` exists on `AuditEventService`, annotated `@Transactional(readOnly = true)`.
- [ ] Validates: `occurredFrom <= occurredTo` if both present; `metadataValue` requires `metadataKey` (and vice versa); `offset >= 0`; `1 <= pageSize <= 200`. Violations throw `IllegalArgumentException` with a message naming the offending field.
- [ ] Logs at DEBUG with all bound parameters, one structured line.
- [ ] Existing `search(...)`, `record(...)`, `archive(...)` are unchanged; their tests still pass.
- [ ] Service-layer unit tests cover each validation branch and a happy-path delegation to a mocked adapter.

---

## T6 — Controller endpoint `GET /api/v1/audit-events/query`

**Refs**: design §3.1, §3.2, §3.3
**Depends on**: T5
**Size**: 1 PR

### Description
Expose the new endpoint on `AuditEventController` with request-param bindings and a wrapper response DTO. Map `IllegalArgumentException` to 400.

### Definition of Done
- [ ] New `@GetMapping("/query")` handler in `AuditEventController` accepts the params from design §3.1–§3.2 with `@DateTimeFormat(iso = ISO.DATE_TIME)` on the Instant params.
- [ ] `action` and `actor` are bound as `List<String>` (Spring resolves repeated query params automatically).
- [ ] `metadata` is bound as `List<String>` of `key=value` strings; the controller (or a small parser helper) converts this into a `Map<String, String>` for the service, rejecting entries that lack `=`, have an empty key, or have an empty value with `IllegalArgumentException`.
- [ ] Defaults: `offset = 0`, `pageSize = 50`, `sort = desc`, all list/map filters default to empty.
- [ ] Response DTO `QueryResponse(List<AuditEventResponse> items, int offset, int pageSize, long totalCount)` exists.
- [ ] Existing `search`, `record`, `archive` handlers are byte-for-byte unchanged.
- [ ] `@ExceptionHandler(IllegalArgumentException.class)` returns 400 with a small problem JSON `{ "message": "..." }`.
- [ ] MockMvc / `@WebMvcTest` slice covers: param binding (including ISO-8601 timestamps, repeated `action`, repeated `metadata=k=v`), default values, 400 mapping for malformed `metadata` entries, response shape.

---

## T7a — Spring Security baseline

**Refs**: design §3.4
**Depends on**: —
**Size**: 1 PR

### Description
Introduce the minimum Spring Security configuration needed to support role-based access control on the service, without changing the authorization posture of any existing endpoint. This task lands the dependency, the `SecurityFilterChain`, the principal/role-resolution wiring, and a `@EnableMethodSecurity`-style hook so subsequent tasks can apply `@PreAuthorize`. All currently-existing endpoints remain reachable exactly as before this PR.

### Definition of Done
- [ ] `org.springframework.boot:spring-boot-starter-security` (and the auth-platform integration the team uses — JWT resource server, OIDC, header-based, etc.) added to `build.gradle`.
- [ ] A `SecurityFilterChain` bean is configured with:
  - `permitAll()` for `POST /api/v1/audit-events`, `GET /api/v1/audit-events`, `POST /api/v1/audit-events/{id}/archive` (preserve current behavior).
  - `csrf().disable()` for the API path (REST clients).
  - The principal resolver maps incoming credentials to roles `AUDIT_COMPLIANCE`, `AUDIT_SRE`, `AUDIT_SECURITY` when present.
- [ ] `@EnableMethodSecurity` is active so `@PreAuthorize` annotations will be honored once added.
- [ ] An integration test (`@Tag("integration")`) records an event and lists events without supplying any credentials, asserting both succeed (regression guard for the permit-all of legacy endpoints).
- [ ] An integration test asserts that a request with a token carrying role `AUDIT_COMPLIANCE` exposes an `Authentication` containing that authority (smoke test of the resolver wiring; no endpoint enforcement yet).
- [ ] No new `@PreAuthorize` annotations on any controller — that belongs to T7b.

---

## T7b — Apply `@PreAuthorize` to the query endpoint

**Refs**: design §3.3, §3.4
**Depends on**: T6, T7a
**Size**: 1 PR

### Description
With the security baseline (T7a) in place and the query endpoint live (T6), restrict the endpoint to callers holding one of the three audit query roles. The change is one annotation plus its tests; no changes to controller logic.

### Definition of Done
- [ ] `@PreAuthorize("hasAnyAuthority('AUDIT_COMPLIANCE', 'AUDIT_SRE', 'AUDIT_SECURITY')")` (or the project's equivalent expression) annotates the new query handler from T6.
- [ ] The query handler returns 401 for anonymous callers and 403 for callers authenticated with no audit query role.
- [ ] Each of the three roles successfully calls the endpoint and receives a 200 response.
- [ ] Legacy endpoints (`POST`/`GET`/`archive`) retain their pre-T7a behavior — verified by re-running the regression integration test from T7a.
- [ ] Auth integration test (`@Tag("integration")`) covers the 401 / 403 / 200 matrix.
- [ ] No changes to the controller body, request DTO, or response DTO from T6 beyond the annotation.

---

## T8 — End-to-end integration test suite for the query endpoint

**Refs**: requirements (all three personas), design §3, §4
**Depends on**: T6, T7b
**Size**: 1 PR

### Description
A dedicated integration test class exercising the full HTTP-to-Postgres path under `@Tag("integration")`, asserting each persona's user story round-trips correctly.

### Definition of Done
- [ ] `AuditEventQueryIntegrationTest` exists, `@Tag("integration")`, runs under the `integrationTest` Gradle task, uses TestContainers Postgres.
- [ ] Compliance scenario: seed events for actor X across a 30-day window; query with `actor=X`, `occurredFrom`, `occurredTo`, `action=DELETE_RECORD`; assert exact set returned. Then re-run with `action=DELETE_RECORD&action=UPDATE_RECORD` (multi-value) and assert the union is returned.
- [ ] SRE timeline scenario: seed events on `aggregate_id=R` interleaved with other aggregates; query with `aggregateId=R`, `sort=asc`, time window covering the incident; assert ordering by `(occurredAt asc, id asc)` and that no other aggregate's events leak in.
- [ ] Security analyst pagination scenario: seed ~150 events; walk pages with `pageSize=50` from offset 0 → 100 → 150; assert `totalCount=150`, no row appears twice across pages, no row is missed.
- [ ] Concurrent-write scenario: between page 1 and page 2, insert one new event with `occurredAt` inside the already-paged window; assert that the page-2 response (with `occurredTo` pinned at the start of the test) still returns the original ordering and totalCount — codifying the design §4 mitigation.
- [ ] Archived-row exclusion: archived events never appear in the response, regardless of filters.
- [ ] Metadata containment, single pair: `metadata=correlationId=abc-123` returns exactly the seeded matching events.
- [ ] Metadata containment, multi pair: `metadata=correlationId=abc-123&metadata=ip=10.0.0.1` returns only events matching **both** pairs.
- [ ] Metadata malformed entry: `metadata=correlationId` (missing `=value`) returns 400.
- [ ] Test fixtures live under `src/test/resources/query-api/` as JSON.

---

## T9 — Documentation: README API section

**Refs**: design §3
**Depends on**: T6
**Size**: 1 PR

### Description
Document the new endpoint alongside the existing API in the project README.

### Definition of Done
- [ ] README API section adds `GET /api/v1/audit-events/query` with the full param table, the response shape, and a worked-example `curl` for each persona's typical query.
- [ ] Documents that archived rows are not returned by this endpoint.
- [ ] Documents the offset-pagination caveat under concurrent writes (§4) and the `occurredTo`-pinning mitigation, in one short paragraph aimed at API consumers.
- [ ] Spotless / Markdown lint (whatever is configured) passes.
