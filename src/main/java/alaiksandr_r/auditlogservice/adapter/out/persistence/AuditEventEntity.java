package alaiksandr_r.auditlogservice.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

  @Id private UUID id;

  @Column(name = "aggregate_id", nullable = false)
  private String aggregateId;

  @Column(nullable = false)
  private String action;

  @Column(nullable = false)
  private String actor;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, String> metadata;

  @Column(name = "archived_at")
  private Instant archivedAt;

  protected AuditEventEntity() {}

  public AuditEventEntity(
      UUID id,
      String aggregateId,
      String action,
      String actor,
      Instant occurredAt,
      Map<String, String> metadata,
      Instant archivedAt) {
    this.id = id;
    this.aggregateId = aggregateId;
    this.action = action;
    this.actor = actor;
    this.occurredAt = occurredAt;
    this.metadata = metadata;
    this.archivedAt = archivedAt;
  }

  public UUID id() {
    return id;
  }

  public String aggregateId() {
    return aggregateId;
  }

  public String action() {
    return action;
  }

  public String actor() {
    return actor;
  }

  public Instant occurredAt() {
    return occurredAt;
  }

  public Map<String, String> metadata() {
    return metadata;
  }

  public Instant archivedAt() {
    return archivedAt;
  }
}
