package alaiksandr_r.auditlogservice.adapter.out.persistence;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class AuditEventPersistenceAdapter {

  private final SpringDataAuditEventRepository repository;

  public AuditEventPersistenceAdapter(SpringDataAuditEventRepository repository) {
    this.repository = repository;
  }

  public AuditEvent save(AuditEvent event) {
    return toDomain(repository.save(toEntity(event)));
  }

  public Optional<AuditEvent> findById(UUID id) {
    return repository.findById(id).map(this::toDomain);
  }

  public List<AuditEvent> search(String aggregateId, String action, String actor) {
    Specification<AuditEventEntity> spec =
        Specification.where(AuditEventSpecifications.isNotArchived())
            .and(AuditEventSpecifications.hasAggregateId(aggregateId))
            .and(AuditEventSpecifications.hasAction(action))
            .and(AuditEventSpecifications.hasActor(actor));

    Sort sort = Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("id"));

    return repository.findAll(spec, sort).stream().map(this::toDomain).toList();
  }

  private AuditEventEntity toEntity(AuditEvent event) {
    return new AuditEventEntity(
        event.id(),
        event.aggregateId(),
        event.action(),
        event.actor(),
        event.occurredAt(),
        event.metadata(),
        event.archivedAt());
  }

  private AuditEvent toDomain(AuditEventEntity entity) {
    return new AuditEvent(
        entity.id(),
        entity.aggregateId(),
        entity.action(),
        entity.actor(),
        entity.occurredAt(),
        entity.metadata(),
        entity.archivedAt());
  }
}
