package alaiksandr_r.auditlogservice.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import alaiksandr_r.auditlogservice.adapter.out.persistence.AuditEventEntity;
import alaiksandr_r.auditlogservice.adapter.out.persistence.SpringDataAuditEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventQueryIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("audit_log")
          .withUsername("audit_log")
          .withPassword("audit_log");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SpringDataAuditEventRepository repository;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void complianceOfficerFindsExactMatchingActorActionAndWindow() throws Exception {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");

    AuditEventEntity match1 =
        event("rec-1", "DELETE_RECORD", "user-A", Instant.parse("2026-01-05T10:00:00Z"));
    AuditEventEntity match2 =
        event("rec-2", "DELETE_RECORD", "user-A", Instant.parse("2026-01-25T10:00:00Z"));
    AuditEventEntity wrongAction =
        event("rec-3", "UPDATE_RECORD", "user-A", Instant.parse("2026-01-10T10:00:00Z"));
    AuditEventEntity wrongActor =
        event("rec-4", "DELETE_RECORD", "user-B", Instant.parse("2026-01-15T10:00:00Z"));
    AuditEventEntity outsideWindow =
        event("rec-5", "DELETE_RECORD", "user-A", Instant.parse("2026-02-05T10:00:00Z"));
    repository.saveAll(List.of(match1, match2, wrongAction, wrongActor, outsideWindow));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/audit-events/query")
                    .param("actor", "user-A")
                    .param("action", "DELETE_RECORD")
                    .param("occurredFrom", from.toString())
                    .param("occurredTo", to.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(2))
            .andReturn();

    Set<UUID> ids = idsOf(result);
    assertThat(ids).containsExactlyInAnyOrder(match1.id(), match2.id());
  }

  @Test
  void sreTimelineReturnsOnlyOneAggregateInAscendingOrder() throws Exception {
    Instant t0 = Instant.parse("2026-03-10T08:00:00Z");

    AuditEventEntity inc1 = event("R", "CREATED", "u1", t0.plusSeconds(10));
    AuditEventEntity inc2 = event("R", "UPDATED", "u1", t0.plusSeconds(30));
    AuditEventEntity inc3 = event("R", "UPDATED", "u2", t0.plusSeconds(60));
    AuditEventEntity inc4 = event("R", "DELETED", "u1", t0.plusSeconds(120));
    AuditEventEntity noise1 = event("S", "UPDATED", "u3", t0.plusSeconds(40));
    AuditEventEntity noise2 = event("T", "UPDATED", "u3", t0.plusSeconds(70));
    repository.saveAll(List.of(inc1, inc2, inc3, inc4, noise1, noise2));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/audit-events/query")
                    .param("aggregateId", "R")
                    .param("sort", "asc")
                    .param("occurredFrom", t0.toString())
                    .param("occurredTo", t0.plusSeconds(300).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(4))
            .andReturn();

    List<UUID> ids = orderedIdsOf(result);
    assertThat(ids).containsExactly(inc1.id(), inc2.id(), inc3.id(), inc4.id());
  }

  @Test
  void securityAnalystWalksAllPagesWithoutLossOrDuplication() throws Exception {
    Instant base = Instant.parse("2026-04-01T00:00:00Z");
    List<AuditEventEntity> seeds = new ArrayList<>();
    for (int i = 0; i < 150; i++) {
      seeds.add(event("walk", "ACTION", "user", base.plusSeconds(i)));
    }
    repository.saveAll(seeds);

    Set<UUID> seenIds = new HashSet<>();
    long totalCount = -1L;
    for (int offset = 0; offset < 150; offset += 50) {
      MvcResult result =
          mockMvc
              .perform(
                  get("/api/v1/audit-events/query")
                      .param("aggregateId", "walk")
                      .param("offset", String.valueOf(offset))
                      .param("pageSize", "50")
                      .param("sort", "asc"))
              .andExpect(status().isOk())
              .andReturn();
      JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
      assertThat(body.get("items")).hasSize(50);
      totalCount = body.get("totalCount").asLong();
      for (JsonNode item : body.get("items")) {
        boolean added = seenIds.add(UUID.fromString(item.get("id").asText()));
        assertThat(added).as("no duplicates across pages").isTrue();
      }
    }

    assertThat(totalCount).isEqualTo(150L);
    assertThat(seenIds).hasSize(150);
  }

  @Test
  void pinnedOccurredToExcludesInsertsArrivingMidTraversal() throws Exception {
    Instant base = Instant.parse("2026-05-01T00:00:00Z");
    Instant pinUpper = base.plusSeconds(1000);
    List<AuditEventEntity> seeds = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      seeds.add(event("conc", "ACTION", "user", base.plusSeconds(i)));
    }
    repository.saveAll(seeds);
    Set<UUID> originalIds = new HashSet<>();
    for (AuditEventEntity e : seeds) {
      originalIds.add(e.id());
    }

    MvcResult page1 =
        mockMvc
            .perform(
                get("/api/v1/audit-events/query")
                    .param("aggregateId", "conc")
                    .param("occurredTo", pinUpper.toString())
                    .param("offset", "0")
                    .param("pageSize", "50")
                    .param("sort", "asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(100))
            .andReturn();
    List<UUID> page1Ids = orderedIdsOf(page1);

    // Per design §4, the mitigation against concurrent inserts is to pin occurredTo: any event
    // arriving with occurredAt >= pinUpper is excluded by the half-open window. Verify the late
    // insert is filtered out and page 2 sees exactly the original ordering.
    repository.save(event("conc", "ACTION", "user", pinUpper.plusSeconds(60)));

    MvcResult page2 =
        mockMvc
            .perform(
                get("/api/v1/audit-events/query")
                    .param("aggregateId", "conc")
                    .param("occurredTo", pinUpper.toString())
                    .param("offset", "50")
                    .param("pageSize", "50")
                    .param("sort", "asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(100))
            .andReturn();
    List<UUID> page2Ids = orderedIdsOf(page2);

    assertThat(page2Ids).hasSize(50);
    assertThat(page2Ids).doesNotContainAnyElementsOf(page1Ids);
    Set<UUID> combined = new HashSet<>(page1Ids);
    combined.addAll(page2Ids);
    assertThat(combined).isEqualTo(originalIds);
  }

  @Test
  void archivedEventsNeverAppearRegardlessOfFilters() throws Exception {
    Instant t = Instant.parse("2026-06-01T00:00:00Z");

    AuditEventEntity live1 = event("a", "ACT", "user", t);
    AuditEventEntity live2 = event("a", "ACT", "user", t.plusSeconds(60));
    AuditEventEntity archived1 =
        new AuditEventEntity(
            UUID.randomUUID(), "a", "ACT", "user", t.plusSeconds(30), Map.of(), t.plusSeconds(35));
    AuditEventEntity archived2 =
        new AuditEventEntity(
            UUID.randomUUID(), "b", "ACT", "user", t.plusSeconds(90), Map.of(), t.plusSeconds(95));
    repository.saveAll(List.of(live1, live2, archived1, archived2));

    MvcResult unfiltered =
        mockMvc.perform(get("/api/v1/audit-events/query")).andExpect(status().isOk()).andReturn();
    assertThat(idsOf(unfiltered))
        .containsExactlyInAnyOrder(live1.id(), live2.id())
        .doesNotContain(archived1.id(), archived2.id());

    MvcResult filtered =
        mockMvc
            .perform(get("/api/v1/audit-events/query").param("aggregateId", "a"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(idsOf(filtered))
        .containsExactlyInAnyOrder(live1.id(), live2.id())
        .doesNotContain(archived1.id());
  }

  @Test
  void queryEndpointMeetsP95LatencyBudgetAtMaxPageSize() throws Exception {
    // CR-4.1 names a 500-row page, but CR-2.1/CR-3.1 cap pageSize at 200; the spec text is
    // inconsistent. Test at the contract maximum (200) since that is the largest page real
    // callers can actually request.
    Instant base = Instant.parse("2026-07-01T00:00:00Z");
    List<AuditEventEntity> seeds = new ArrayList<>(1000);
    for (int i = 0; i < 1000; i++) {
      seeds.add(event("perf", "ACTION", "user", base.plusSeconds(i)));
    }
    repository.saveAll(seeds);

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(get("/api/v1/audit-events/query").param("pageSize", "200"))
          .andExpect(status().isOk());
    }

    long[] durationsMs = new long[20];
    for (int i = 0; i < 20; i++) {
      long start = System.nanoTime();
      mockMvc
          .perform(get("/api/v1/audit-events/query").param("pageSize", "200"))
          .andExpect(status().isOk());
      durationsMs[i] = (System.nanoTime() - start) / 1_000_000L;
    }
    Arrays.sort(durationsMs);
    long p95 = durationsMs[18]; // ceil(0.95 * 20) - 1 = 18

    assertThat(p95)
        .as("p95 latency @ pageSize=200 over 1000 seeded rows must be <= 300 ms")
        .isLessThanOrEqualTo(300L);
  }

  private static AuditEventEntity event(
      String aggregateId, String action, String actor, Instant occurredAt) {
    return new AuditEventEntity(
        UUID.randomUUID(), aggregateId, action, actor, occurredAt, Map.of(), null);
  }

  private Set<UUID> idsOf(MvcResult result) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Set<UUID> ids = new HashSet<>();
    for (JsonNode item : body.get("items")) {
      ids.add(UUID.fromString(item.get("id").asText()));
    }
    return ids;
  }

  private List<UUID> orderedIdsOf(MvcResult result) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    List<UUID> ids = new ArrayList<>();
    for (JsonNode item : body.get("items")) {
      ids.add(UUID.fromString(item.get("id").asText()));
    }
    return ids;
  }
}
