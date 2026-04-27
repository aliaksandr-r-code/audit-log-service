package alaiksandr_r.auditlogservice.adapter.in.messaging;

import java.util.Map;

public record AuditEventMessage(
    String aggregateId, String action, String actor, Map<String, String> metadata) {}
