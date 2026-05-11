package alaiksandr_r.auditlogservice.adapter.out.persistence;

import alaiksandr_r.auditlogservice.application.service.QueryAuditEventsQuery;
import alaiksandr_r.auditlogservice.application.service.QueryAuditEventsResult;
import alaiksandr_r.auditlogservice.application.service.SortDirection;
import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  public QueryAuditEventsResult query(QueryAuditEventsQuery q) {
    Specification<AuditEventEntity> spec =
        Specification.where(AuditEventSpecifications.isNotArchived())
            .and(AuditEventSpecifications.hasAggregateId(q.aggregateId()))
            .and(AuditEventSpecifications.hasAction(q.action()))
            .and(AuditEventSpecifications.hasActor(q.actor()))
            .and(AuditEventSpecifications.occurredAtAtLeast(q.occurredFrom()))
            .and(AuditEventSpecifications.occurredAtBefore(q.occurredTo()));

    Sort.Direction direction =
        q.sort() == SortDirection.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
    Sort sort = Sort.by(direction, "occurredAt").and(Sort.by(direction, "id"));

    Page<AuditEventEntity> page =
        repository.findAll(spec, offsetPageable(q.offset(), q.pageSize(), sort));

    return new QueryAuditEventsResult(
        page.getContent().stream().map(this::toDomain).toList(),
        q.offset(),
        q.pageSize(),
        page.getTotalElements());
  }

  // PageRequest.of(page, size) computes offset as page * size, so it cannot express arbitrary
  // row offsets (e.g. offset=75, pageSize=50). This minimal Pageable returns the caller-supplied
  // offset verbatim, which Spring Data uses as the SQL OFFSET.
  private static Pageable offsetPageable(int offset, int pageSize, Sort sort) {
    return new Pageable() {
      @Override
      public int getPageNumber() {
        return offset / pageSize;
      }

      @Override
      public int getPageSize() {
        return pageSize;
      }

      @Override
      public long getOffset() {
        return offset;
      }

      @Override
      public Sort getSort() {
        return sort;
      }

      @Override
      public Pageable next() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Pageable previousOrFirst() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Pageable first() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Pageable withPage(int pageNumber) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean hasPrevious() {
        return offset > 0;
      }
    };
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
