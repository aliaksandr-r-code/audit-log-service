package alaiksandr_r.auditlogservice.adapter.in.web;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import alaiksandr_r.auditlogservice.domain.port.in.RecordAuditEventCommand;
import alaiksandr_r.auditlogservice.domain.port.in.RecordAuditEventUseCase;
import alaiksandr_r.auditlogservice.domain.port.in.SearchAuditEventsQuery;
import alaiksandr_r.auditlogservice.domain.port.in.SearchAuditEventsUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

  private final RecordAuditEventUseCase recordAuditEventUseCase;
  private final SearchAuditEventsUseCase searchAuditEventsUseCase;

  public AuditEventController(
      RecordAuditEventUseCase recordAuditEventUseCase,
      SearchAuditEventsUseCase searchAuditEventsUseCase) {
    this.recordAuditEventUseCase = recordAuditEventUseCase;
    this.searchAuditEventsUseCase = searchAuditEventsUseCase;
  }

  @PostMapping
  public ResponseEntity<AuditEventResponse> record(
      @Valid @RequestBody RecordAuditEventRequest request) {
    AuditEvent event =
        recordAuditEventUseCase.record(
            new RecordAuditEventCommand(
                request.aggregateId(), request.action(), request.actor(), request.metadata()));

    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(event.id())
            .toUri();

    return ResponseEntity.created(location).body(AuditEventResponse.from(event));
  }

  @GetMapping
  public List<AuditEventResponse> search(
      @RequestParam(required = false) String aggregateId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String actor) {
    SearchAuditEventsQuery query = new SearchAuditEventsQuery(aggregateId, action, actor);

    return searchAuditEventsUseCase.search(query).stream().map(AuditEventResponse::from).toList();
  }

  public record RecordAuditEventRequest(
      @NotBlank String aggregateId,
      @NotBlank String action,
      @NotBlank String actor,
      Map<String, String> metadata) {}

  public record AuditEventResponse(
      UUID id,
      String aggregateId,
      String action,
      String actor,
      Instant occurredAt,
      Map<String, String> metadata) {

    static AuditEventResponse from(AuditEvent event) {
      return new AuditEventResponse(
          event.id(),
          event.aggregateId(),
          event.action(),
          event.actor(),
          event.occurredAt(),
          event.metadata());
    }
  }
}
