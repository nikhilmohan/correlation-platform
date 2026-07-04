package com.acp.correlationengine.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.support.EngineHarness;
import com.acp.correlationengine.support.Fixtures;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC19 — a poison message on {@code alarms.persisted.live} is routed to the DLQ and the consumer
 * continues processing the next valid message without halting. Also exercises the AlarmEvent ->
 * ObservedAlarm mapping + per-trail fan-out through the real engine.
 */
class AlarmIngestConsumerTest {

    private final EventCodec codec = new EventCodec();
    private final CorrelationEngineProperties props = new CorrelationEngineProperties(
            "mock", "u", "u", "u", "core-ip", 1000, 1000, "off", "u", "mock", 2, null);

    @Test
    void ac19_poisonMessageRoutedToDlq_processingContinues() {
        EngineHarness h = new EngineHarness();
        h.addPattern(Fixtures.gapPattern("P", "T1", List.of("LOS", "LinkDown"), "LOS", 60_000));
        DlqProducer dlq = mock(DlqProducer.class);
        AlarmIngestConsumer consumer = new AlarmIngestConsumer(h.engine, codec, dlq, props);

        // 1) poison -> DLQ, engine untouched
        consumer.onMessage("{ not valid json");
        verify(dlq).route(eq("alarms.persisted.live"), any(), eq("{ not valid json"), any());
        assertThat(h.engine.totalAlarmsProcessed()).isZero();

        // 2) the NEXT valid message is still processed (no halt)
        consumer.onMessage(validAlarm("a1", "LOS", List.of("T1")));
        assertThat(h.engine.totalAlarmsProcessed()).isEqualTo(1);
        assertThat(h.engine.hasInstance("T1", "P")).isTrue();
    }

    @Test
    void validAlarm_fansOutToTrails() {
        EngineHarness h = new EngineHarness();
        h.addPattern(Fixtures.gapPattern("Pa", "T1", List.of("LOS", "LinkDown"), "LOS", 60_000));
        h.addPattern(Fixtures.gapPattern("Pb", "T2", List.of("LOS", "PortDown"), "LOS", 60_000));
        DlqProducer dlq = mock(DlqProducer.class);
        AlarmIngestConsumer consumer = new AlarmIngestConsumer(h.engine, codec, dlq, props);

        consumer.onMessage(validAlarm("a1", "LOS", List.of("T1", "T2")));

        verifyNoInteractions(dlq);
        assertThat(h.engine.hasInstance("T1", "Pa")).isTrue();
        assertThat(h.engine.hasInstance("T2", "Pb")).isTrue();
    }

    private String validAlarm(String alarmId, String alarmType, List<String> trailIds) {
        AlarmEvent event = new AlarmEvent()
                .withAlarmId(alarmId)
                .withManagedObjectId("router:R1")
                .withEventType("communicationsAlarm")
                .withProbableCause("lossOfSignal")
                .withAlarmType(alarmType)
                .withPerceivedSeverity("critical")
                .withRaisedAt("2026-06-11T12:00:00Z")
                .withState(AlarmEvent.State.RAISED)
                .withTrailIds(trailIds);
        TypedEnvelope<AlarmEvent> envelope = new TypedEnvelope<>(
                "evt-" + alarmId, "AlarmEvent", 1, "2026-06-11T12:00:00Z",
                "alarm-manager", "trace-1", event);
        return codec.serialize(envelope);
    }
}
