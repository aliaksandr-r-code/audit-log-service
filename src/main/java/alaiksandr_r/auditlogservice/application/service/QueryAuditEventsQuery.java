package alaiksandr_r.auditlogservice.application.service;

import java.time.Instant;

public record QueryAuditEventsQuery(
    String aggregateId,
    String action,
    String actor,
    Instant occurredFrom,
    Instant occurredTo,
    SortDirection sort,
    int offset,
    int pageSize) {}
