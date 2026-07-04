package com.acp.correlationengine.integration;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import com.acp.correlationengine.model.Incident;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.CorrelationResultEvent;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka-backed {@link CorrelationResultEmitter} — emits one {@code CorrelationResultEvent} on
 * {@code correlation.results} per committed incident (persist-then-emit), keyed by {@code incidentId}
 * for per-incident ordering. The payload is built through the frozen event-model binding and the
 * canonical wire JSON is validated by {@link EventCodec#serialize} before publish, so no off-contract
 * bytes can leave the service. {@code matchedCodebookId} is the active codebook artifact id on a
 * codebook-decode incident and null on a pattern-match incident (AC13/AC14/AC15/AC25).
 */
public class KafkaCorrelationResultEmitter implements CorrelationResultEmitter {

    private final KafkaTemplate<String, String> kafka;
    private final EventCodec codec;
    private final String topic;

    public KafkaCorrelationResultEmitter(KafkaTemplate<String, String> kafka, EventCodec codec,
            CorrelationEngineProperties props) {
        this.kafka = kafka;
        this.codec = codec;
        this.topic = props.topics().correlationResults();
    }

    @Override
    public void emit(Incident incident) {
        CorrelationResultEvent payload = new CorrelationResultEvent()
                .withIncidentId(incident.incidentId())
                .withRootCauseAlarmId(incident.rootCauseAlarmId())
                .withChildAlarmIds(incident.childAlarmIds())
                .withMatchedPatternId(incident.matchedPatternId())
                .withMatchedCodebookId(incident.matchedCodebookId())
                .withConfidence(incident.confidence())
                .withTrailId(incident.trailId());
        TypedEnvelope<CorrelationResultEvent> envelope =
                EventEnvelopes.wrap("CorrelationResultEvent", payload);
        kafka.send(topic, incident.incidentId(), codec.serialize(envelope));
    }
}
