package alaiksandr_r.auditlogservice.application.service;

import java.util.Map;

public record RecordAuditEventCommand(
    String aggregateId, String action, String actor, Map<String, String> metadata) {}
