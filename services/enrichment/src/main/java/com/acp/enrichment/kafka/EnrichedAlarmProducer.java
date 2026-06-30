package com.acp.enrichment.kafka;

import com.acp.enrichment.config.EnrichmentProperties;
import com.acp.enrichment.pipeline.Path;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Serializes an enriched canonical {@code AlarmEvent} via the {@link EventCodec} (re-validating the
 * canonical-output invariant on serialize) and sends it to the topic chosen by {@link Path}:
 * {@code alarms.enriched} for HISTORY, {@code alarms.enriched.live} for LIVE (spec criteria 7, 8;
 * design step 8). The output envelope {@code source} is {@code enrichment} (this service is now the
 * originator); the input {@code traceId} is propagated.
 */
@Component
public class EnrichedAlarmProducer {

    /** The output envelope source — this service is the originator of the enriched alarm. */
    public static final String OUTPUT_SOURCE = "enrichment";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec codec;
    private final EnrichmentProperties props;
    private final MeterRegistry meters;

    public EnrichedAlarmProducer(KafkaTemplate<String, String> kafkaTemplate, EventCodec codec,
            EnrichmentProperties props, MeterRegistry meters) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.props = props;
        this.meters = meters;
    }

    /**
     * @param alarm the enriched canonical alarm
     * @param path the originating path (selects the output topic)
     * @param occurredAt the input envelope {@code occurredAt} (propagated)
     * @param traceId the input envelope {@code traceId} (propagated)
     * @param source the resolved source (for metrics)
     */
    public void emit(AlarmEvent alarm, Path path, String occurredAt, String traceId,
            String source) {
        String topic = path == Path.LIVE ? props.getEnrichedLiveTopic() : props.getEnrichedTopic();
        TypedEnvelope<AlarmEvent> envelope = new TypedEnvelope<>(
                UUID.randomUUID().toString(), "AlarmEvent", 1, occurredAt, OUTPUT_SOURCE,
                traceId, alarm);
        // serialize() re-validates against the frozen AlarmEvent schema (canonical-output invariant).
        String json = codec.serialize(envelope);
        kafkaTemplate.send(topic, alarm.getManagedObjectId(), json);
        meters.counter("alarms_emitted_total", "path", path.name(), "source", source).increment();
    }
}
