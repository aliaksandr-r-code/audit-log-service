package alaiksandr_r.auditlogservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class QueryAuditEventsQueryTest {

  @Test
  void exposesAllFieldsAsAccessors() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");

    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(
            "agg-1", "DELETE_RECORD", "user-7", from, to, SortDirection.DESC, 0, 50);

    assertThat(query.aggregateId()).isEqualTo("agg-1");
    assertThat(query.action()).isEqualTo("DELETE_RECORD");
    assertThat(query.actor()).isEqualTo("user-7");
    assertThat(query.occurredFrom()).isEqualTo(from);
    assertThat(query.occurredTo()).isEqualTo(to);
    assertThat(query.sort()).isEqualTo(SortDirection.DESC);
    assertThat(query.offset()).isZero();
    assertThat(query.pageSize()).isEqualTo(50);
  }

  @Test
  void allowsNullFiltersAndBounds() {
    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.ASC, 0, 50);

    assertThat(query.aggregateId()).isNull();
    assertThat(query.action()).isNull();
    assertThat(query.actor()).isNull();
    assertThat(query.occurredFrom()).isNull();
    assertThat(query.occurredTo()).isNull();
  }
}
