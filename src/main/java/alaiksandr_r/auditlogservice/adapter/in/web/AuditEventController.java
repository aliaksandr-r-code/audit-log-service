package alaiksandr_r.auditlogservice.adapter.in.web;

import alaiksandr_r.auditlogservice.application.service.AuditEventService;
import alaiksandr_r.auditlogservice.application.service.RecordAuditEventCommand;
import alaiksandr_r.auditlogservice.application.service.SearchAuditEventsQuery;
import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import alaiksandr_r.auditlogservice.domain.model.AuditEventNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

  private final AuditEventService auditEventService;

  public AuditEventController(AuditEventService auditEventService) {
    this.auditEventService = auditEventService;
  }

  @PostMapping
  public ResponseEntity<AuditEventResponse> record(
      @Valid @RequestBody RecordAuditEventRequest request) {
    AuditEvent event =
        auditEventService.record(
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
    return auditEventService
        .search(new SearchAuditEventsQuery(aggregateId, action, actor))
        .stream()
        .map(AuditEventResponse::from)
        .toList();
  }

  @PostMapping("/{id}/archive")
  public AuditEventResponse archive(@PathVariable UUID id) {
    return AuditEventResponse.from(auditEventService.archive(id));
  }

  @ExceptionHandler(AuditEventNotFoundException.class)
  public ResponseEntity<Void> handleNotFound() {
    return ResponseEntity.notFound().build();
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
      Map<String, String> metadata,
      Instant archivedAt) {

    static AuditEventResponse from(AuditEvent event) {
      return new AuditEventResponse(
          event.id(),
          event.aggregateId(),
          event.action(),
          event.actor(),
          event.occurredAt(),
          event.metadata(),
          event.archivedAt());
    }
  }
}
