package com.acp.correlationengine.integration;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmStatusChange;
import java.time.Instant;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka-backed {@link AlarmStatusEmitter} — fires one {@code AlarmStatusChange} on
 * {@code alarms.status.changed} per alarm per lifecycle transition ({@code in-progress} on
 * admission, {@code correlated} on full match, {@code reverted-open} on session expiry),
 * {@code source = correlation-engine}, keyed by {@code alarmId} for per-alarm ordering. No transition
 * is silently omitted (AC6/AC4/AC5/AC22). Validated through the frozen event-model binding.
 */
public class KafkaAlarmStatusEmitter implements AlarmStatusEmitter {

    private final KafkaTemplate<String, String> kafka;
    private final EventCodec codec;
    private final String topic;

    public KafkaAlarmStatusEmitter(KafkaTemplate<String, String> kafka, EventCodec codec,
            CorrelationEngineProperties props) {
        this.kafka = kafka;
        this.codec = codec;
        this.topic = props.topics().alarmsStatusChanged();
    }

    @Override
    public void fireInProgress(String alarmId, long changedAtEpochMs) {
        fire(alarmId, AlarmStatusChange.NewStatus.IN_PROGRESS, changedAtEpochMs);
    }

    @Override
    public void fireCorrelated(String alarmId, long changedAtEpochMs) {
        fire(alarmId, AlarmStatusChange.NewStatus.CORRELATED, changedAtEpochMs);
    }

    @Override
    public void fireRevertedOpen(String alarmId, long changedAtEpochMs) {
        fire(alarmId, AlarmStatusChange.NewStatus.REVERTED_OPEN, changedAtEpochMs);
    }

    private void fire(String alarmId, AlarmStatusChange.NewStatus status, long changedAtEpochMs) {
        AlarmStatusChange payload = new AlarmStatusChange()
                .withAlarmId(alarmId)
                .withNewStatus(status)
                .withSource(SOURCE)
                .withChangedAt(Instant.ofEpochMilli(changedAtEpochMs).toString());
        TypedEnvelope<AlarmStatusChange> envelope = EventEnvelopes.wrap("AlarmStatusChange", payload);
        kafka.send(topic, alarmId, codec.serialize(envelope));
    }
}
