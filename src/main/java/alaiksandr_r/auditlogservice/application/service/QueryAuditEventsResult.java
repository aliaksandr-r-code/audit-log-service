package alaiksandr_r.auditlogservice.application.service;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import java.util.List;

public record QueryAuditEventsResult(
    List<AuditEvent> items, int offset, int pageSize, long totalCount) {

  public QueryAuditEventsResult {
    items = List.copyOf(items);
  }
}
