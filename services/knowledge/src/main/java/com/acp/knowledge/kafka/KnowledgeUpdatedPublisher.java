package com.acp.knowledge.kafka;

import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.KnowledgeUpdatedEvent;
import com.acp.knowledge.config.KafkaTopicProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code Envelope} + {@code KnowledgeUpdatedEvent} payload and produces to
 * {@code knowledge.updated} with the idempotent producer.
 *
 * <p>The {@code eventId} is minted <b>once per change</b> (a stable UUID tied to the specific
 * version change) and reused across the producer's internal retries — so a redelivered event is
 * recognised by a consumer as a duplicate (AC15). The wire JSON is produced via the frozen
 * event-model {@link EventCodec}, which validates the payload against the frozen
 * {@code KnowledgeUpdatedEvent} JSON Schema before emit, so an off-contract event can never be
 * published.
 */
@Component
@ConditionalOnBean(KafkaTemplate.class)
public class KnowledgeUpdatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUpdatedPublisher.class);
    private static final String SOURCE = "knowledge";

    private final KafkaTemplate<String, String> kafka;
    private final KafkaTopicProperties topicProperties;
    private final EventCodec codec = new EventCodec();
    private final Counter published;
    private final Counter publishFailures;

    public KnowledgeUpdatedPublisher(KafkaTemplate<String, String> knowledgeKafkaTemplate,
            KafkaTopicProperties topicProperties, MeterRegistry registry) {
        this.kafka = knowledgeKafkaTemplate;
        this.topicProperties = topicProperties;
        this.published = Counter.builder("knowledge_updated_published_total")
                .description("knowledge.updated events successfully produced").register(registry);
        this.publishFailures = Counter.builder("knowledge_updated_publish_failures_total")
                .description("knowledge.updated produce failures").register(registry);
    }

    /**
     * Emit a {@code knowledge.updated} event for a persisted change.
     *
     * @return the wire JSON that was produced (also useful for tests / dedupe inspection)
     */
    public String publish(String domain, String recordType, String recordId, String version) {
        String eventId = UUID.randomUUID().toString();
        return publishWithEventId(eventId, domain, recordType, recordId, version);
    }

    /**
     * Emit with a caller-supplied {@code eventId} — the stable UUID tied to the change. Reused
     * across producer retries so the same logical change always carries the same {@code eventId}.
     */
    public String publishWithEventId(String eventId, String domain, String recordType,
            String recordId, String version) {
        String wire = buildWire(eventId, domain, recordType, recordId, version);
        try {
            kafka.send(topicProperties.updatedTopic(), recordId, wire)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            publishFailures.increment();
                            log.error("knowledge.updated publish failed eventId={} recordType={} "
                                            + "recordId={} version={} domain={}",
                                    eventId, recordType, recordId, version, domain, ex);
                        } else {
                            published.increment();
                            log.info("knowledge.updated published eventId={} recordType={} "
                                            + "recordId={} version={} domain={}",
                                    eventId, recordType, recordId, version, domain);
                        }
                    });
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.error("knowledge.updated send error eventId={} recordType={} recordId={}",
                    eventId, recordType, recordId, e);
        }
        return wire;
    }

    /** Build (and contract-validate) the wire JSON for a change without sending it. */
    public String buildWire(String eventId, String domain, String recordType, String recordId,
            String version) {
        KnowledgeUpdatedEvent payload = new KnowledgeUpdatedEvent();
        payload.setRecordType(recordType);
        payload.setRecordId(recordId);
        payload.setVersion(version);
        payload.setDomain(domain);

        TypedEnvelope<KnowledgeUpdatedEvent> envelope = new TypedEnvelope<>(
                eventId,
                "KnowledgeUpdatedEvent",
                1,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                SOURCE,
                UUID.randomUUID().toString(),
                payload);
        return codec.serialize(envelope);
    }
}
