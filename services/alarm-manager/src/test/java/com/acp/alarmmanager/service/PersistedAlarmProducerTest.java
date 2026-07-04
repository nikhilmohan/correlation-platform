package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * AC2 — the same AlarmEvent is republished on alarms.persisted.live and round-trips against the
 * frozen binding. AC3 (republish half) — the published-once guard means no second emit on
 * redelivery.
 */
class PersistedAlarmProducerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = Mockito.mock(KafkaTemplate.class);
    private final AlarmRepository alarms = Mockito.mock(AlarmRepository.class);
    private final EventCodec codec = new EventCodec();
    private final AmMetrics metrics = Mockito.mock(AmMetrics.class);

    private PersistedAlarmProducer producer;

    @BeforeEach
    void setUp() {
        producer = new PersistedAlarmProducer(kafka, alarms, codec, metrics,
                new AlarmManagerProperties());
    }

    @Test
    void republishesSameAlarmEventValidAgainstBinding() {
        when(alarms.markPublished(eq("ALM-0001"), any())).thenReturn(true);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        producer.republish("ALM-0001", env);

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> wire = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(topic.capture(), eq("ALM-0001"), wire.capture());
        assertThat(topic.getValue()).isEqualTo("alarms.persisted.live");

        // The republished wire deserializes back to an equal AlarmEvent (frozen binding round-trip).
        TypedEnvelope<Object> roundTrip = codec.deserialize(wire.getValue());
        assertThat(roundTrip.getType()).isEqualTo("AlarmEvent");
        AlarmEvent payload = (AlarmEvent) roundTrip.getPayload();
        assertThat(payload.getAlarmId()).isEqualTo("ALM-0001");
        assertThat(payload.getAlarmType()).isEqualTo("PortDown");
        assertThat(payload.getManagedObjectId()).isEqualTo("Port:ne1-1-1");
    }

    @Test
    void redeliveryDoesNotProduceSecondEmit() {
        // First call wins the flip; second finds published already true.
        when(alarms.markPublished(eq("ALM-0001"), any())).thenReturn(true).thenReturn(false);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        producer.republish("ALM-0001", env);
        producer.republish("ALM-0001", env);

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotEmitWhenAlreadyPublished() {
        when(alarms.markPublished(eq("ALM-0001"), any())).thenReturn(false);
        TypedEnvelope<Object> env = Fixtures.alarmEnvelope("ALM-0001", "PortDown", "raised");

        producer.republish("ALM-0001", env);

        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
