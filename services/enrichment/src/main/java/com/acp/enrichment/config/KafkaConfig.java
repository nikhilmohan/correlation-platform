package com.acp.enrichment.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

/**
 * Explicit, idempotent Kafka configuration (no auto-config shortcuts on the consumer side):
 * <ul>
 *   <li><b>Producer</b> — idempotent ({@code enable.idempotence=true}, {@code acks=all}) so the
 *       output and DLQ are exactly-once-on-the-broker / at-least-once end-to-end.</li>
 *   <li><b>Consumer</b> — manual immediate ack ({@code AckMode.MANUAL_IMMEDIATE}),
 *       {@code enable.auto.commit=false}, {@code auto.offset.reset=earliest}; the listener
 *       acknowledges after handling (success / drop / DLQ) so a poison message never blocks the
 *       partition and is never silently lost (criterion 15).</li>
 * </ul>
 * Idempotency / dedupe is on {@code eventId} (envelope) plus the {@code (source, managedObjectId,
 * eventType)} window (alarm-level), per the platform's at-least-once guarantee.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${kafka.bootstrap-servers:${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}}")
            String bootstrap) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            @Value("${kafka.bootstrap-servers:${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}}")
            String bootstrap,
            @Value("${enrichment.consumer-group:enrichment}") String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
