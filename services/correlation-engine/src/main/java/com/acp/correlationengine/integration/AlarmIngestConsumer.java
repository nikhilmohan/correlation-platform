package com.acp.correlationengine.integration;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;

/**
 * Consumes {@code alarms.persisted.live} ({@code AlarmEvent}): validates through the frozen
 * event-model binding, then fans the alarm out to each trail's active correlation instances via
 * {@link CorrelationEngine#onAlarm}. Deduplication on {@code alarmId} is enforced inside the engine.
 * Unparseable/off-contract messages are routed to {@code alarms.persisted.live.dlq} and processing
 * continues with the next record (AC19).
 */
public class AlarmIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmIngestConsumer.class);

    private final CorrelationEngine engine;
    private final EventCodec codec;
    private final DlqProducer dlq;
    private final String topic;

    public AlarmIngestConsumer(CorrelationEngine engine, EventCodec codec, DlqProducer dlq,
            CorrelationEngineProperties props) {
        this.engine = engine;
        this.codec = codec;
        this.dlq = dlq;
        this.topic = props.topics().alarmsPersistedLive();
    }

    @KafkaListener(
            topics = "${correlation-engine.topics.alarms-persisted-live}",
            groupId = "correlation-engine-alarms")
    public void onMessage(@Payload String raw) {
        AlarmEvent event;
        try {
            TypedEnvelope<Object> envelope = codec.deserialize(raw);
            if (!(envelope.getPayload() instanceof AlarmEvent parsed)) {
                dlq.route(topic, null, raw,
                        new IllegalArgumentException("expected AlarmEvent, got " + envelope.getType()));
                return;
            }
            event = parsed;
        } catch (RuntimeException e) {
            dlq.route(topic, null, raw, e);
            return;
        }
        ObservedAlarm observed = AlarmEventMapper.toObserved(event);
        List<String> trailIds = AlarmEventMapper.trailIds(event);
        engine.onAlarm(observed, trailIds, Instant.now().toEpochMilli());
        log.debug("Processed alarm {} across {} trails", event.getAlarmId(), trailIds.size());
    }
}
