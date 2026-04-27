package alaiksandr_r.auditlogservice.domain.port.in;

import java.util.Map;

public record RecordAuditEventCommand(
    String aggregateId, String action, String actor, Map<String, String> metadata) {}
