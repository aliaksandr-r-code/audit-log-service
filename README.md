# Audit Log Service

REST API for recording and querying immutable audit events within a company (e.g. Teams messages, user actions).

## Features

- Record audit events
- Search events by `aggregateId`, `action`, and/or `actor`
- Archive events (excluded from search results after archiving)

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
