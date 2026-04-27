package alaiksandr_r.auditlogservice.application.service;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import alaiksandr_r.auditlogservice.domain.port.in.RecordAuditEventCommand;
import alaiksandr_r.auditlogservice.domain.port.in.RecordAuditEventUseCase;
import alaiksandr_r.auditlogservice.domain.port.in.SearchAuditEventsQuery;
import alaiksandr_r.auditlogservice.domain.port.in.SearchAuditEventsUseCase;
import alaiksandr_r.auditlogservice.domain.port.out.AuditEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditEventService implements RecordAuditEventUseCase, SearchAuditEventsUseCase {

  private final AuditEventRepository repository;
  private final Clock clock;

  public AuditEventService(AuditEventRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public AuditEvent record(RecordAuditEventCommand command) {
    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID(),
            command.aggregateId(),
            command.action(),
            command.actor(),
            clock.instant(),
            command.metadata());

    return repository.append(event);
  }

  @Override
  public List<AuditEvent> search(SearchAuditEventsQuery query) {
    return repository.search(query);
  }
}
