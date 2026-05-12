# Audit Log Service

REST API for recording and querying immutable audit events within a company (e.g. Teams messages, user actions).

## Features

- Record audit events
- Search events by `aggregateId`, `action`, and/or `actor`
- Query events with time-window filtering, deterministic ordering, and offset pagination
- Archive events (excluded from search and query results after archiving)

## Technology Stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Database | PostgreSQL |
| Migrations | Flyway |
| Persistence | Spring Data JPA (Hibernate) |
| Build | Gradle |

## Architecture

Hexagonal architecture: inbound REST adapter → application service → outbound persistence adapter → Spring Data JPA.

```
adapter/in/web/         REST (AuditEventController)
application/service/    Business logic (AuditEventService)
adapter/out/persistence/JPA persistence (AuditEventPersistenceAdapter)
domain/model/           Domain objects (AuditEvent)
```

Events are immutable — archiving creates a new version with `archivedAt` set.

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/audit-events` | Record a new event |
| `GET` | `/api/v1/audit-events` | Search events |
| `GET` | `/api/v1/audit-events/query` | Query events with filters, pagination, and sort |
| `POST` | `/api/v1/audit-events/{id}/archive` | Archive an event |

### Record an event

```http
POST /api/v1/audit-events
Content-Type: application/json

{
  "aggregateId": "teams-message-1",
  "action": "MESSAGE_CREATED",
  "actor": "user-7",
  "metadata": {
    "channelId": "engineering"
  }
}
```

`metadata` is optional. `occurredAt` is set automatically.

### Search events

```http
GET /api/v1/audit-events?aggregateId=teams-message-1&action=MESSAGE_CREATED&actor=user-7
```

All query parameters are optional. Archived events are excluded. Results are ordered by `occurredAt` ascending.

### Query events

```http
GET /api/v1/audit-events/query
```

Read-only endpoint that adds time-window filtering, deterministic ordering, and offset pagination on top of the existing filters.

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `aggregateId` | string | — | exact match, optional |
| `action` | string | — | exact match, optional |
| `actor` | string | — | exact match, optional |
| `occurredFrom` | ISO-8601 timestamp | — | inclusive lower bound, optional |
| `occurredTo` | ISO-8601 timestamp | — | exclusive upper bound, optional |
| `offset` | int | `0` | row offset, must be `>= 0` |
| `pageSize` | int | `50` | page size, must be in `[1, 200]` |
| `sort` | `asc` \| `desc` | `desc` | direction of `occurredAt` ordering |

Filters are AND-combined; omitting a filter drops it from the predicate. Results are ordered by `(occurredAt, id)` in the requested direction — `id` is the deterministic tie-break when events share `occurredAt`.

Response shape:

```json
{
  "items": [
    {
      "id": "…",
      "aggregateId": "…",
      "action": "…",
      "actor": "…",
      "occurredAt": "…",
      "metadata": { },
      "archivedAt": null
    }
  ],
  "offset": 0,
  "pageSize": 50,
  "totalCount": 123
}
```

`totalCount` is the snapshot count of rows matching the filters at query time. Returns `400` with `{ "message": "…" }` for `occurredFrom > occurredTo`, `offset < 0`, `pageSize` outside `[1, 200]`, or a malformed ISO-8601 timestamp.

**Compliance officer** — confirm a specific action by a specific actor in an audit window:

```bash
curl 'http://localhost:8080/api/v1/audit-events/query?actor=user-7&action=DELETE_RECORD&occurredFrom=2026-01-01T00:00:00Z&occurredTo=2026-02-01T00:00:00Z'
```

**SRE** — reconstruct the timeline of actions on one resource during an incident:

```bash
curl 'http://localhost:8080/api/v1/audit-events/query?aggregateId=resource-42&occurredFrom=2026-03-10T14:00:00Z&occurredTo=2026-03-10T16:00:00Z&sort=asc'
```

**Security analyst** — paginate a large result set:

```bash
curl 'http://localhost:8080/api/v1/audit-events/query?occurredFrom=2026-01-01T00:00:00Z&pageSize=50&offset=0'
curl 'http://localhost:8080/api/v1/audit-events/query?occurredFrom=2026-01-01T00:00:00Z&pageSize=50&offset=50'
```

#### Caveats

- Archived events are never returned, regardless of filters.
- Pagination is offset-based, so concurrent writes between page requests can shift offsets — a new event landing inside the visible window can cause a row to repeat on the next page, and a concurrent archive can cause one to be skipped. To get a stable traversal, pin `occurredTo` to a timestamp at or before the start of the walk: inserts arriving past that bound are excluded by the half-open window. (See `.specs/query-api/design.MD` §4.)

### Archive an event

```http
POST /api/v1/audit-events/{id}/archive
```

Returns `404` if the event does not exist.

## Running Locally

**Prerequisites:** Docker, Java 21

Start PostgreSQL:

```bash
docker compose up -d
```

Run the application:

```bash
./gradlew bootRun
```

The service starts on port `8080`. Health check: `http://localhost:8080/actuator/health`

## Building

```bash
./gradlew build
```

This runs Spotless style checks and integration tests.

### Individual tasks

| Command | Description |
|---------|-------------|
| `./gradlew test` | Unit tests |
| `./gradlew integrationTest` | Integration tests (requires Docker) |
| `./gradlew spotlessCheck` | Code style check |
| `./gradlew spotlessApply` | Auto-format code |
| `./gradlew sonar` | SonarQube analysis |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/audit_log` | Database URL |
| `SPRING_DATASOURCE_USERNAME` | `audit_log` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `audit_log` | Database password |
| `SERVER_PORT` | `8080` | HTTP port |
