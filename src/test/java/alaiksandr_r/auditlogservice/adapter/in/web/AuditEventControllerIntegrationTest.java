package alaiksandr_r.auditlogservice.adapter.in.web;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  @Test
  void recordsViaRestAndSearches() throws Exception {
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
        .andExpect(jsonPath("$.metadata.channelId").value("engineering"));

    mockMvc
        .perform(get("/api/v1/audit-events").param("aggregateId", "teams-message-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].aggregateId").value("teams-message-1"))
        .andExpect(jsonPath("$[0].action").value("MESSAGE_CREATED"))
        .andExpect(jsonPath("$[0].actor").value("user-7"));
  }

  @Test
  void consumesKafkaMessageAndPersistsAuditEvent() throws Exception {
    String message =
        new ClassPathResource("messages/kafka-audit-event-message.json")
            .getContentAsString(StandardCharsets.UTF_8)
            .strip();

    Properties producerProps = new Properties();
    producerProps.put("bootstrap.servers", kafka.getBootstrapServers());
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
      producer.send(new ProducerRecord<>("audit.events", "teams-message-99", message)).get();
    }

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(get("/api/v1/audit-events").param("aggregateId", "teams-message-99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].aggregateId").value("teams-message-99"))
                    .andExpect(jsonPath("$[0].action").value("MESSAGE_DELETED"))
                    .andExpect(jsonPath("$[0].actor").value("user-3")));
  }
}
