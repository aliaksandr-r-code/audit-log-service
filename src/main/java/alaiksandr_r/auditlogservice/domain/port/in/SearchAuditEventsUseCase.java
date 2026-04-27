package alaiksandr_r.auditlogservice.domain.port.in;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import java.util.List;

public interface SearchAuditEventsUseCase {

  List<AuditEvent> search(SearchAuditEventsQuery query);
}
