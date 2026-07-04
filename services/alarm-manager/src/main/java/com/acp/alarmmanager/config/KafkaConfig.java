package com.acp.alarmmanager.config;

import com.acp.eventmodel.EventCodec;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Explicit, idempotent Kafka config.
 *
 * <p><b>Producer</b> ({@code alarms.persisted.live} + the three DLQ topics): {@code
 * enable.idempotence=true}, {@code acks=all}, bounded in-flight, retries. The value is the
 * canonical wire JSON string produced by the {@link EventCodec} (faithful re-serialize of the
 * consumed payload), so no double-emit and no off-contract bytes.
 *
 * <p><b>Consumers</b>: the raw record value is delivered as {@code byte[]} (via
 * {@link ByteArrayDeserializer}) so the {@link EventCodec} performs schema validation in-service;
 * a codec failure is routed to the matching {@code <topic>.dlq} rather than crashing a JSON
 * deserializer before the service can DLQ it. Manual/immediate ack (offset committed only after
 * successful processing or DLQ routing). {@code auto.offset.reset=earliest} and dedupe on
 * {@code alarmId} / envelope {@code eventId} make at-least-once delivery safe.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private final AlarmManagerProperties.Kafka cfg;

    public KafkaConfig(AlarmManagerProperties properties) {
        this.cfg = properties.getKafka();
    }

    /** The frozen event-model codec (schema-validating deserialize + faithful serialize). */
    @Bean
    public EventCodec eventCodec() {
        return new EventCodec();
    }

    // --- Producer (idempotent) --------------------------------------------------------------

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }

    // --- Consumers (byte[] value; manual immediate ack) -------------------------------------

    private ConsumerFactory<String, byte[]> consumerFactory(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    private ConcurrentKafkaListenerContainerFactory<String, byte[]> listenerFactory(String groupId) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(groupId));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> enrichedListenerFactory() {
        return listenerFactory(cfg.getGroupIdEnriched());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> statusListenerFactory() {
        return listenerFactory(cfg.getGroupIdStatus());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> correlationListenerFactory() {
        return listenerFactory(cfg.getGroupIdCorrelation());
    }
}
