package com.acp.knowledge.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Idempotent Kafka producer for {@code knowledge.updated}.
 *
 * <p>Per the design's "Producer config (idempotent)": {@code enable.idempotence=true},
 * {@code acks=all}, {@code max.in.flight.requests.per.connection=5}, retries bounded by the
 * delivery timeout. Keys are the {@code recordId} (string) so all versions of a record land on
 * one partition in order. The {@code eventId} is minted once per change and reused across the
 * broker's internal retries — so a redelivered event carries the same {@code eventId} (consumer
 * idempotency, AC15). Values are the pre-serialized canonical wire JSON (String).
 *
 * <p>Guarded by {@code knowledge.kafka.enabled} (default true) so unit/slice tests that do not
 * exercise the producer can disable it without a broker.
 */
@Configuration
@ConditionalOnProperty(name = "knowledge.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> knowledgeProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Idempotent, ordered, durable delivery.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> knowledgeKafkaTemplate(
            ProducerFactory<String, String> knowledgeProducerFactory) {
        return new KafkaTemplate<>(knowledgeProducerFactory);
    }
}
