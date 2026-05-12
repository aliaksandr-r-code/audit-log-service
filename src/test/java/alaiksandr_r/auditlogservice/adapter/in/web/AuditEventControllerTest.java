package alaiksandr_r.auditlogservice.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import alaiksandr_r.auditlogservice.application.service.AuditEventService;
import alaiksandr_r.auditlogservice.application.service.QueryAuditEventsQuery;
import alaiksandr_r.auditlogservice.application.service.QueryAuditEventsResult;
import alaiksandr_r.auditlogservice.application.service.SortDirection;
import alaiksandr_r.auditlogservice.domain.model.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditEventController.class)
@Import(AuditEventControllerTest.RecordingServiceConfig.class)
class AuditEventControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RecordingAuditEventService service;

  @BeforeEach
  void resetRecorder() {
    service.lastQuery = null;
    service.nextResult = new QueryAuditEventsResult(List.of(), 0, 50, 0L);
    service.nextException = null;
  }

  @Test
  void queryAppliesDefaultsWhenNoParamsProvided() throws Exception {
    mockMvc
        .perform(get("/api/v1/audit-events/query"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.offset").value(0))
        .andExpect(jsonPath("$.pageSize").value(50))
        .andExpect(jsonPath("$.totalCount").value(0));

    assertThat(service.lastQuery.aggregateId()).isNull();
    assertThat(service.lastQuery.action()).isNull();
    assertThat(service.lastQuery.actor()).isNull();
    assertThat(service.lastQuery.occurredFrom()).isNull();
    assertThat(service.lastQuery.occurredTo()).isNull();
    assertThat(service.lastQuery.sort()).isEqualTo(SortDirection.DESC);
    assertThat(service.lastQuery.offset()).isZero();
    assertThat(service.lastQuery.pageSize()).isEqualTo(50);
  }

  @Test
  void queryBindsFiltersAndPaginationAndIsoTimestamps() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit-events/query")
                .param("aggregateId", "agg-1")
                .param("action", "MESSAGE_CREATED")
                .param("actor", "user-7")
                .param("occurredFrom", "2026-01-01T00:00:00Z")
                .param("occurredTo", "2026-02-01T00:00:00Z")
                .param("offset", "20")
                .param("pageSize", "10")
                .param("sort", "asc"))
        .andExpect(status().isOk());

    assertThat(service.lastQuery.aggregateId()).isEqualTo("agg-1");
    assertThat(service.lastQuery.action()).isEqualTo("MESSAGE_CREATED");
    assertThat(service.lastQuery.actor()).isEqualTo("user-7");
    assertThat(service.lastQuery.occurredFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(service.lastQuery.occurredTo()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    assertThat(service.lastQuery.offset()).isEqualTo(20);
    assertThat(service.lastQuery.pageSize()).isEqualTo(10);
    assertThat(service.lastQuery.sort()).isEqualTo(SortDirection.ASC);
  }

  @Test
  void queryMapsEventsIntoResponseDto() throws Exception {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    AuditEvent event =
        new AuditEvent(
            id,
            "agg-1",
            "MESSAGE_CREATED",
            "user-7",
            Instant.parse("2026-01-15T12:00:00Z"),
            Map.of("channelId", "engineering"),
            null);
    service.nextResult = new QueryAuditEventsResult(List.of(event), 0, 50, 1L);

    mockMvc
        .perform(get("/api/v1/audit-events/query"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(id.toString()))
        .andExpect(jsonPath("$.items[0].aggregateId").value("agg-1"))
        .andExpect(jsonPath("$.items[0].action").value("MESSAGE_CREATED"))
        .andExpect(jsonPath("$.items[0].actor").value("user-7"))
        .andExpect(jsonPath("$.items[0].metadata.channelId").value("engineering"))
        .andExpect(jsonPath("$.totalCount").value(1));
  }

  @Test
  void queryReturns400WhenServiceRejectsValidation() throws Exception {
    service.nextException = () -> new IllegalArgumentException("pageSize must be in [1, 200]");

    mockMvc
        .perform(get("/api/v1/audit-events/query").param("pageSize", "999"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("pageSize must be in [1, 200]"));
  }

  @Test
  void queryReturns400WhenOccurredFromIsNotIso8601() throws Exception {
    mockMvc
        .perform(get("/api/v1/audit-events/query").param("occurredFrom", "not-a-date"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryReturns400WhenOffsetIsNotAnInteger() throws Exception {
    mockMvc
        .perform(get("/api/v1/audit-events/query").param("offset", "abc"))
        .andExpect(status().isBadRequest());
  }

  @TestConfiguration
  static class RecordingServiceConfig {
    @Bean
    @Primary
    RecordingAuditEventService recordingAuditEventService() {
      return new RecordingAuditEventService();
    }
  }

  static class RecordingAuditEventService extends AuditEventService {
    QueryAuditEventsQuery lastQuery;
    QueryAuditEventsResult nextResult = new QueryAuditEventsResult(List.of(), 0, 50, 0L);
    java.util.function.Supplier<RuntimeException> nextException;

    RecordingAuditEventService() {
      super(null, Clock.systemUTC());
    }

    @Override
    public QueryAuditEventsResult query(QueryAuditEventsQuery q) {
      this.lastQuery = q;
      if (nextException != null) {
        throw nextException.get();
      }
      return nextResult;
    }
  }
}
