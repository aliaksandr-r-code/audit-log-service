package alaiksandr_r.auditlogservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import alaiksandr_r.auditlogservice.adapter.out.persistence.AuditEventPersistenceAdapter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditEventServiceTest {

  private final RecordingAdapter adapter = new RecordingAdapter();
  private final AuditEventService service = new AuditEventService(adapter, Clock.systemUTC());

  @Test
  void queryDelegatesToAdapterOnHappyPath() {
    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.DESC, 0, 50);
    QueryAuditEventsResult expected = new QueryAuditEventsResult(List.of(), 0, 50, 0L);
    adapter.nextResult = expected;

    QueryAuditEventsResult actual = service.query(query);

    assertThat(actual).isSameAs(expected);
    assertThat(adapter.lastQuery).isSameAs(query);
  }

  @Test
  void queryRejectsOccurredFromAfterOccurredTo() {
    Instant from = Instant.parse("2026-02-01T00:00:00Z");
    Instant to = Instant.parse("2026-01-01T00:00:00Z");
    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(null, null, null, from, to, SortDirection.DESC, 0, 50);

    assertThatThrownBy(() -> service.query(query))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("occurredFrom");

    assertThat(adapter.callCount).isZero();
  }

  @Test
  void queryAcceptsEqualOccurredFromAndOccurredToAndOpenBounds() {
    Instant moment = Instant.parse("2026-01-15T00:00:00Z");

    service.query(
        new QueryAuditEventsQuery(null, null, null, moment, moment, SortDirection.DESC, 0, 50));
    service.query(
        new QueryAuditEventsQuery(null, null, null, moment, null, SortDirection.DESC, 0, 50));
    service.query(
        new QueryAuditEventsQuery(null, null, null, null, moment, SortDirection.DESC, 0, 50));

    assertThat(adapter.callCount).isEqualTo(3);
  }

  @Test
  void queryRejectsNegativeOffset() {
    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.DESC, -1, 50);

    assertThatThrownBy(() -> service.query(query))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset");

    assertThat(adapter.callCount).isZero();
  }

  @Test
  void queryRejectsPageSizeBelowOne() {
    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.DESC, 0, 0);

    assertThatThrownBy(() -> service.query(query))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize");

    assertThat(adapter.callCount).isZero();
  }

  @Test
  void queryRejectsPageSizeAboveTwoHundred() {
    QueryAuditEventsQuery query =
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.DESC, 0, 201);

    assertThatThrownBy(() -> service.query(query))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize");

    assertThat(adapter.callCount).isZero();
  }

  @Test
  void queryAcceptsPageSizeAtBothBounds() {
    service.query(
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.DESC, 0, 1));
    service.query(
        new QueryAuditEventsQuery(null, null, null, null, null, SortDirection.DESC, 0, 200));

    assertThat(adapter.callCount).isEqualTo(2);
  }

  private static final class RecordingAdapter extends AuditEventPersistenceAdapter {
    QueryAuditEventsQuery lastQuery;
    QueryAuditEventsResult nextResult = new QueryAuditEventsResult(List.of(), 0, 50, 0L);
    int callCount;

    RecordingAdapter() {
      super(null);
    }

    @Override
    public QueryAuditEventsResult query(QueryAuditEventsQuery q) {
      this.lastQuery = q;
      this.callCount++;
      return nextResult;
    }
  }
}
