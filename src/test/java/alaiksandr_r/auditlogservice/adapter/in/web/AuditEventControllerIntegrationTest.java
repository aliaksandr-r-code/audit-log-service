package alaiksandr_r.auditlogservice.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import alaiksandr_r.auditlogservice.adapter.out.persistence.SpringDataAuditEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
class AuditEventControllerIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("audit_log")
          .withUsername("audit_log")
          .withPassword("audit_log");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SpringDataAuditEventRepository repository;

  @BeforeEach
  void cleanUp() {
    repository.deleteAll();
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void recordsEventAndSearchesByAggregateId() throws Exception {
    String requestBody =
        new ClassPathResource("requests/record-audit-event-request.json")
            .getContentAsString(StandardCharsets.UTF_8);

    mockMvc
        .perform(
            post("/api/v1/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, notNullValue()))
        .andExpect(jsonPath("$.id", notNullValue()))
        .andExpect(jsonPath("$.aggregateId").value("teams-message-1"))
        .andExpect(jsonPath("$.action").value("MESSAGE_CREATED"))
        .andExpect(jsonPath("$.actor").value("user-7"))
        .andExpect(jsonPath("$.occurredAt", notNullValue()))
        .andExpect(jsonPath("$.metadata.channelId").value("engineering"));

    mockMvc
        .perform(get("/api/v1/audit-events").param("aggregateId", "teams-message-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].aggregateId").value("teams-message-1"))
        .andExpect(jsonPath("$[0].action").value("MESSAGE_CREATED"))
        .andExpect(jsonPath("$[0].actor").value("user-7"))
        .andExpect(jsonPath("$[0].metadata.channelId").value("engineering"));
  }

  @Test
  void recordsEventWithoutMetadataAndReturnsEmptyMetadata() throws Exception {
    String requestBody =
        new ClassPathResource("requests/record-audit-event-without-metadata-request.json")
            .getContentAsString(StandardCharsets.UTF_8);

    mockMvc
        .perform(
            post("/api/v1/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, notNullValue()))
        .andExpect(jsonPath("$.id", notNullValue()))
        .andExpect(jsonPath("$.aggregateId").value("teams-message-2"))
        .andExpect(jsonPath("$.action").value("MESSAGE_UPDATED"))
        .andExpect(jsonPath("$.actor").value("user-5"))
        .andExpect(jsonPath("$.occurredAt", notNullValue()))
        .andExpect(jsonPath("$.metadata", notNullValue()));
  }

  @Test
  void returnsEmptyListWhenNoEventsMatchQuery() throws Exception {
    mockMvc
        .perform(get("/api/v1/audit-events").param("aggregateId", "non-existent-aggregate-id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void returnsBadRequestWhenRequiredFieldsAreMissing() throws Exception {
    mockMvc
        .perform(post("/api/v1/audit-events").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void archivesEventAndExcludesItFromSearch() throws Exception {
    String requestBody =
        new ClassPathResource("requests/record-audit-event-request.json")
            .getContentAsString(StandardCharsets.UTF_8);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/v1/audit-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
    String id = created.get("id").asText();

    mockMvc
        .perform(post("/api/v1/audit-events/{id}/archive", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.archivedAt", notNullValue()));

    mockMvc
        .perform(get("/api/v1/audit-events").param("aggregateId", "teams-message-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + id + "')]", hasSize(0)));
  }

  @Test
  void returnsNotFoundWhenArchivingNonExistentEvent() throws Exception {
    mockMvc
        .perform(post("/api/v1/audit-events/{id}/archive", "00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isNotFound());
  }

  @Test
  void recordedEventHasNullArchivedAt() throws Exception {
    String requestBody =
        new ClassPathResource("requests/record-audit-event-request.json")
            .getContentAsString(StandardCharsets.UTF_8);

    mockMvc
        .perform(
            post("/api/v1/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.archivedAt", nullValue()));
  }
}
