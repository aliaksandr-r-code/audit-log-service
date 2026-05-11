package alaiksandr_r.auditlogservice.application.service;

import alaiksandr_r.auditlogservice.adapter.out.persistence.AuditEventPersistenceAdapter;
import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import alaiksandr_r.auditlogservice.domain.model.AuditEventNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventService {

  private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

  private final AuditEventPersistenceAdapter persistence;
  private final Clock clock;

  public AuditEventService(AuditEventPersistenceAdapter persistence, Clock clock) {
    this.persistence = persistence;
    this.clock = clock;
  }

  @Transactional
  public AuditEvent record(RecordAuditEventCommand command) {
    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID(),
            command.aggregateId(),
            command.action(),
            command.actor(),
            clock.instant(),
            command.metadata(),
            null);
    AuditEvent saved = persistence.save(event);
    log.info(
        "Recorded audit event: id={}, aggregateId={}, action={}, actor={}",
        saved.id(),
        saved.aggregateId(),
        saved.action(),
        saved.actor());
    return saved;
  }

  @Transactional(readOnly = true)
  public List<AuditEvent> search(SearchAuditEventsQuery query) {
    log.debug(
        "Searching audit events: aggregateId={}, action={}, actor={}",
        query.aggregateId(),
        query.action(),
        query.actor());
    return persistence.search(
        nullIfBlank(query.aggregateId()), nullIfBlank(query.action()), nullIfBlank(query.actor()));
  }

  @Transactional(readOnly = true)
  public QueryAuditEventsResult query(QueryAuditEventsQuery query) {
    validate(query);
    log.debug(
        "Querying audit events: aggregateId={}, action={}, actor={}, occurredFrom={},"
            + " occurredTo={}, sort={}, offset={}, pageSize={}",
        query.aggregateId(),
        query.action(),
        query.actor(),
        query.occurredFrom(),
        query.occurredTo(),
        query.sort(),
        query.offset(),
        query.pageSize());
    return persistence.query(query);
  }

  private static void validate(QueryAuditEventsQuery query) {
    if (query.occurredFrom() != null
        && query.occurredTo() != null
        && query.occurredFrom().isAfter(query.occurredTo())) {
      throw new IllegalArgumentException("occurredFrom must be <= occurredTo");
    }
    if (query.offset() < 0) {
      throw new IllegalArgumentException("offset must be >= 0");
    }
    if (query.pageSize() < 1 || query.pageSize() > 200) {
      throw new IllegalArgumentException("pageSize must be in [1, 200]");
    }
  }

  @Transactional
  public AuditEvent archive(UUID id) {
    AuditEvent event =
        persistence.findById(id).orElseThrow(() -> new AuditEventNotFoundException(id));
    AuditEvent archived =
        persistence.save(
            new AuditEvent(
                event.id(),
                event.aggregateId(),
                event.action(),
                event.actor(),
                event.occurredAt(),
                event.metadata(),
                clock.instant()));
    log.info("Archived audit event: id={}", archived.id());
    return archived;
  }

  private String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
