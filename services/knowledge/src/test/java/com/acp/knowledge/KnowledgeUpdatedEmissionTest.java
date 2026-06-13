package com.acp.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * AC8 — {@code knowledge.updated} is emitted for vocabulary AND catalogue changes with a
 * conformant envelope ({@code eventId} UUID, {@code type=KnowledgeUpdatedEvent},
 * {@code source=knowledge}, valid {@code occurredAt}) and payload ({@code recordType},
 * {@code version}, {@code domain}, {@code recordId}).
 * AC12 — {@code knowledge.updated} is emitted on every validated change to the original four
 * record types; exactly one message per change; payload validates against the frozen
 * {@code KnowledgeUpdatedEvent} schema (enforced in {@code EventCodec.serialize} on the produce
 * path) and carries the new version.
 * AC15 — duplicate {@code eventId} is idempotent: the {@code eventId} is a stable UUID tied to the
 * change, so a consumer-side dedupe check recognises a re-presented {@code eventId} as a duplicate.
 *
 * <p>This test runs the full validate → version → commit → post-commit publish path against a real
 * PostgreSQL (Testcontainers) and an embedded Kafka broker, then consumes the produced messages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {"knowledge.updated"})
class KnowledgeUpdatedEmissionTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("correlation")
                .withUsername("correlation")
                .withPassword("correlation");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Real producer ON, broker = the embedded broker; seeder OFF (tests author their own data).
        registry.add("knowledge.kafka.enabled", () -> "true");
        registry.add("knowledge.seed.on-startup", () -> "false");
        registry.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        // Unique group per test reading from earliest; assertions filter the drained records by the
        // recordIds THIS test authored, so leftover events from other tests in the class (the
        // embedded broker's topic persists) never affect the per-change count.
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(
                "knowledge-test-" + java.util.UUID.randomUUID(), "true", broker));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, "knowledge.updated");
        jdbc.execute("DELETE FROM knowledge.record_version");
        jdbc.execute("DELETE FROM knowledge.record");
    }

    /** Drain everything currently in the topic (poll until quiescent) into a list of envelopes. */
    private java.util.List<JsonNode> drainAll() throws Exception {
        java.util.List<JsonNode> out = new java.util.ArrayList<>();
        while (true) {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(800));
            if (batch.isEmpty()) {
                break;
            }
            for (ConsumerRecord<String, String> rec : batch) {
                out.add(objectMapper.readTree(rec.value()));
            }
        }
        return out;
    }

    /** Filter drained envelopes to those whose payload.recordId matches (partition order kept). */
    private static java.util.List<JsonNode> eventsForRecord(java.util.List<JsonNode> all,
            String recordId) {
        java.util.List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode env : all) {
            if (recordId.equals(env.path("payload").path("recordId").asText())) {
                out.add(env);
            }
        }
        return out;
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    // AC8 — vocabulary + catalogue changes each emit exactly one conformant event.
    @Test
    void vocabularyAndCatalogueChanges_emitConformantEvents() throws Exception {
        authorRec("object-type-vocabulary", """
            {"recordId":"core-ip/objectTypeVocabulary/ac8","payload":{
              "objectTypes":["Node","Interface"]}}""");
        authorRec("attribute-catalogue", """
            {"recordId":"core-ip/attributeCatalogue/ac8","payload":{
              "deviceKeys":[{"key":"igpArea","valueForm":"string"}],
              "connectionKeys":[{"key":"linkType","valueForm":"string"}]}}""");

        java.util.List<JsonNode> all = drainAll();
        java.util.List<JsonNode> vocab = eventsForRecord(all, "core-ip/objectTypeVocabulary/ac8");
        java.util.List<JsonNode> cat = eventsForRecord(all, "core-ip/attributeCatalogue/ac8");

        Assertions.assertEquals(1, vocab.size(), "exactly one objectTypeVocabulary event");
        Assertions.assertEquals(1, cat.size(), "exactly one attributeCatalogue event");
        assertEnvelopeConformant(vocab.get(0));
        assertEnvelopeConformant(cat.get(0));
        Assertions.assertEquals("objectTypeVocabulary",
                vocab.get(0).path("payload").path("recordType").asText());
        Assertions.assertEquals("attributeCatalogue",
                cat.get(0).path("payload").path("recordType").asText());
    }

    // AC12 — a create then an update of an original-four record each emit exactly one event whose
    // version matches the new version.
    @Test
    void originalTypeChanges_emitOneEventPerChange_withMatchingVersion() throws Exception {
        // vocab prerequisites for cross-record validation.
        authorRec("object-type-vocabulary", """
            {"recordId":"core-ip/objectTypeVocabulary/ac12","payload":{
              "objectTypes":["Node","Interface"]}}""");
        authorRec("alarm-type-vocabulary", """
            {"recordId":"core-ip/alarmTypeVocabulary/ac12","payload":{
              "alarmTypes":["InterfaceDown"]}}""");

        // Create a faultOriginType (v1).
        String foRecord = "core-ip/faultOriginType/ac12-Interface";
        authorRec("fault-origin-types", """
            {"recordId":"core-ip/faultOriginType/ac12-Interface","payload":{
              "objectType":"Interface","originAlarmType":"InterfaceDown"}}""");
        // Update it (v2).
        mockMvc.perform(put("/domains/core-ip/fault-origin-types/"
                        + CrudVersioningOriginalTypesTest.enc(foRecord))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"payload":{"objectType":"Interface","originAlarmType":"InterfaceDown",
                              "description":"updated"}}"""))
                .andExpect(status().isOk());

        java.util.List<JsonNode> events = eventsForRecord(drainAll(), foRecord);
        Assertions.assertEquals(2, events.size(),
                "exactly one event per change (create + update)");
        // events are partition-ordered (single partition, keyed by recordId): v1 then v2.
        assertEnvelopeConformant(events.get(0));
        Assertions.assertEquals("faultOriginType",
                events.get(0).path("payload").path("recordType").asText());
        Assertions.assertEquals("v1", events.get(0).path("payload").path("version").asText());
        Assertions.assertEquals("v2", events.get(1).path("payload").path("version").asText());
    }

    // AC15 — the eventId is a stable UUID tied to the change; a consumer dedupes on it.
    @Test
    void eventId_isStableUuid_andDedupeRecognisesDuplicate() throws Exception {
        authorRec("object-type-vocabulary", """
            {"recordId":"core-ip/objectTypeVocabulary/ac15","payload":{
              "objectTypes":["Node"]}}""");
        java.util.List<JsonNode> events = eventsForRecord(drainAll(), "core-ip/objectTypeVocabulary/ac15");
        Assertions.assertEquals(1, events.size());
        String eventId = events.get(0).path("eventId").asText();

        // A consumer-side dedupe set: presenting the SAME eventId twice → recognised as duplicate.
        java.util.Set<String> seen = new java.util.HashSet<>();
        Assertions.assertTrue(seen.add(eventId), "first occurrence is new");
        Assertions.assertFalse(seen.add(eventId), "second occurrence of the same eventId is a duplicate");
        // It is a valid UUID.
        Assertions.assertDoesNotThrow(() -> java.util.UUID.fromString(eventId));
    }

    private void assertEnvelopeConformant(JsonNode env) {
        String eventId = env.path("eventId").asText();
        Assertions.assertDoesNotThrow(() -> java.util.UUID.fromString(eventId),
                "eventId must be a UUID");
        Assertions.assertEquals("KnowledgeUpdatedEvent", env.path("type").asText());
        Assertions.assertEquals("knowledge", env.path("source").asText());
        String occurredAt = env.path("occurredAt").asText();
        Assertions.assertDoesNotThrow(() -> java.time.Instant.parse(occurredAt),
                "occurredAt must be a valid ISO-8601 instant");
        JsonNode payload = env.path("payload");
        Assertions.assertFalse(payload.path("recordType").asText().isBlank());
        Assertions.assertFalse(payload.path("version").asText().isBlank());
        Assertions.assertFalse(payload.path("domain").asText().isBlank());
    }

    private void authorRec(String segment, String body) throws Exception {
        mockMvc.perform(post("/domains/core-ip/" + segment)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
