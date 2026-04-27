package alaiksandr_r.auditlogservice.adapter.in.messaging;

import alaiksandr_r.auditlogservice.domain.port.in.RecordAuditEventCommand;
import alaiksandr_r.auditlogservice.domain.port.in.RecordAuditEventUseCase;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class KafkaAuditEventConsumer {

  private final RecordAuditEventUseCase recordUseCase;

  KafkaAuditEventConsumer(RecordAuditEventUseCase recordUseCase) {
    this.recordUseCase = recordUseCase;
  }

  @KafkaListener(topics = "${audit.kafka.topic}")
  void consume(AuditEventMessage message) {
    recordUseCase.record(
        new RecordAuditEventCommand(
            message.aggregateId(), message.action(), message.actor(), message.metadata()));
  }
}
