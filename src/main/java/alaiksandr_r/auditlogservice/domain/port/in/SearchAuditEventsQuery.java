package alaiksandr_r.auditlogservice.domain.port.in;

public record SearchAuditEventsQuery(String aggregateId, String action, String actor) {}
