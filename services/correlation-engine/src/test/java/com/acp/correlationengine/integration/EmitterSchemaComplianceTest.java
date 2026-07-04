package com.acp.correlationengine.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmStatusChange;
import com.acp.eventmodel.generated.CorrelationResultEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Schema-compliance of the produced events (AC13, AC22, AC25). Every emitted event is captured as
 * the on-the-wire JSON the emitter passes to Kafka, then re-validated by deserializing it through the
 * frozen event-model {@link EventCodec} — proving the payload satisfies the frozen schema and all
 * required fields are present.
 */
class EmitterSchemaComplianceTest {

    private final EventCodec codec = new EventCodec();
    private final CorrelationEngineProperties props = new CorrelationEngineProperties(
            "mock", "u", "u", "u", "core-ip", 1000, 1000, "off", null);

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockTemplate() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return kafka;
    }

    /** AC13 — every CorrelationResultEvent validates against the frozen schema; required fields present. */
    @Test
    void ac13_correlationResultEvent_isSchemaCompliant() {
        KafkaTemplate<String, String> kafka = mockTemplate();
        KafkaCorrelationResultEmitter emitter = new KafkaCorrelationResultEmitter(kafka, codec, props);

        Incident incident = new Incident("INC-1", "T1", "root", "LOS",
                List.of("c1", "c2"), "PAT-1", null, 0.91,
                MatchCandidate.MatchType.PATTERN, "fp", Instant.parse("2026-06-11T12:00:00Z"));
        emitter.emit(incident);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("correlation.results"), eq("INC-1"), json.capture());

        TypedEnvelope<Object> parsed = codec.deserialize(json.getValue());
        assertThat(parsed.getType()).isEqualTo("CorrelationResultEvent");
        CorrelationResultEvent p = (CorrelationResultEvent) parsed.getPayload();
        assertThat(p.getIncidentId()).isEqualTo("INC-1");
        assertThat(p.getRootCauseAlarmId()).isEqualTo("root");
        assertThat(p.getChildAlarmIds()).containsExactly("c1", "c2");
        assertThat(p.getConfidence()).isEqualTo(0.91);
        assertThat(p.getTrailId()).isEqualTo("T1");
        assertThat(p.getMatchedPatternId()).isEqualTo("PAT-1");
        assertThat(p.getMatchedCodebookId()).isNull();
    }

    /** AC25 — matchedCodebookId is the codebook artifact id on a codebook-decode incident. */
    @Test
    void ac25_matchedCodebookId_isArtifactIdOnCodebookIncident() {
        KafkaTemplate<String, String> kafka = mockTemplate();
        KafkaCorrelationResultEmitter emitter = new KafkaCorrelationResultEmitter(kafka, codec, props);

        Incident incident = new Incident("INC-2", "T1", "root", "LOS",
                List.of("c1"), null, "CODEBOOK-9", 0.7,
                MatchCandidate.MatchType.CODEBOOK, "fp2", Instant.parse("2026-06-11T12:00:00Z"));
        emitter.emit(incident);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), json.capture());
        CorrelationResultEvent p = (CorrelationResultEvent) codec.deserialize(json.getValue()).getPayload();
        assertThat(p.getMatchedCodebookId()).isEqualTo("CODEBOOK-9");
        assertThat(p.getMatchedPatternId()).isNull();
    }

    /** AC22 — every AlarmStatusChange validates against the frozen schema; source=correlation-engine. */
    @Test
    void ac22_alarmStatusChange_isSchemaCompliant() {
        KafkaTemplate<String, String> kafka = mockTemplate();
        KafkaAlarmStatusEmitter emitter = new KafkaAlarmStatusEmitter(kafka, codec, props);
        long now = Instant.parse("2026-06-11T12:00:00Z").toEpochMilli();

        emitter.fireInProgress("a1", now);
        emitter.fireCorrelated("a2", now);
        emitter.fireRevertedOpen("a3", now);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka, org.mockito.Mockito.times(3))
                .send(eq("alarms.status.changed"), any(), json.capture());

        List<String> emitted = json.getAllValues();
        assertThat(emitted).hasSize(3);
        for (String wire : emitted) {
            TypedEnvelope<Object> parsed = codec.deserialize(wire);
            assertThat(parsed.getType()).isEqualTo("AlarmStatusChange");
            AlarmStatusChange p = (AlarmStatusChange) parsed.getPayload();
            assertThat(p.getSource()).isEqualTo("correlation-engine");
            assertThat(p.getAlarmId()).isNotBlank();
            assertThat(p.getChangedAt()).isNotBlank();
            assertThat(p.getNewStatus()).isNotNull();
        }
        assertThat(((AlarmStatusChange) codec.deserialize(emitted.get(0)).getPayload()).getNewStatus())
                .isEqualTo(AlarmStatusChange.NewStatus.IN_PROGRESS);
        assertThat(((AlarmStatusChange) codec.deserialize(emitted.get(1)).getPayload()).getNewStatus())
                .isEqualTo(AlarmStatusChange.NewStatus.CORRELATED);
        assertThat(((AlarmStatusChange) codec.deserialize(emitted.get(2)).getPayload()).getNewStatus())
                .isEqualTo(AlarmStatusChange.NewStatus.REVERTED_OPEN);
    }
}
