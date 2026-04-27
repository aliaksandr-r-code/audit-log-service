package alaiksandr_r.auditlogservice.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(
    UUID id,
    String aggregateId,
    String action,
    String actor,
    Instant occurredAt,
    Map<String, String> metadata) {

  public AuditEvent {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(aggregateId, "aggregateId must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
