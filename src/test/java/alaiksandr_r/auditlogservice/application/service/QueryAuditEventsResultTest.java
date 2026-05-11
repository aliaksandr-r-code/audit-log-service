package alaiksandr_r.auditlogservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryAuditEventsResultTest {

  @Test
  void exposesAllFieldsAsAccessors() {
    QueryAuditEventsResult result =
        new QueryAuditEventsResult(List.of(sampleEvent()), 10, 50, 123L);

    assertThat(result.items()).hasSize(1);
    assertThat(result.offset()).isEqualTo(10);
    assertThat(result.pageSize()).isEqualTo(50);
    assertThat(result.totalCount()).isEqualTo(123L);
  }

  @Test
  void defensivelyCopiesItemsSoLaterMutationOfSourceDoesNotLeak() {
    List<AuditEvent> mutableSource = new ArrayList<>();
    mutableSource.add(sampleEvent());

    QueryAuditEventsResult result = new QueryAuditEventsResult(mutableSource, 0, 50, 1L);
    mutableSource.add(sampleEvent());

    assertThat(result.items()).hasSize(1);
  }

  @Test
  void returnedItemsListIsUnmodifiable() {
    QueryAuditEventsResult result = new QueryAuditEventsResult(List.of(sampleEvent()), 0, 50, 1L);

    assertThatThrownBy(() -> result.items().add(sampleEvent()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static AuditEvent sampleEvent() {
    return new AuditEvent(
        UUID.randomUUID(),
        "agg-1",
        "MESSAGE_CREATED",
        "user-7",
        Instant.parse("2026-01-15T12:00:00Z"),
        Map.of(),
        null);
  }
}
