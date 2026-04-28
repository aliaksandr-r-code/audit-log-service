package alaiksandr_r.auditlogservice.application.service;

public record SearchAuditEventsQuery(String aggregateId, String action, String actor) {}
