package alaiksandr_r.auditlogservice.adapter.out.persistence;

import org.springframework.data.jpa.domain.Specification;

class AuditEventSpecifications {

  static Specification<AuditEventEntity> hasAggregateId(String aggregateId) {
    return (root, query, cb) ->
        aggregateId == null ? null : cb.equal(root.get("aggregateId"), aggregateId);
  }

  static Specification<AuditEventEntity> hasAction(String action) {
    return (root, query, cb) -> action == null ? null : cb.equal(root.get("action"), action);
  }

  static Specification<AuditEventEntity> hasActor(String actor) {
    return (root, query, cb) -> actor == null ? null : cb.equal(root.get("actor"), actor);
  }

  static Specification<AuditEventEntity> isNotArchived() {
    return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
  }
}
