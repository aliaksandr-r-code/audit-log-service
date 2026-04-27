package alaiksandr_r.auditlogservice.domain.port.in;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;

public interface RecordAuditEventUseCase {

  AuditEvent record(RecordAuditEventCommand command);
}
