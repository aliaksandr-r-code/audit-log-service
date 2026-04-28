package alaiksandr_r.auditlogservice.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataAuditEventRepository
    extends JpaRepository<AuditEventEntity, UUID>, JpaSpecificationExecutor<AuditEventEntity> {}
