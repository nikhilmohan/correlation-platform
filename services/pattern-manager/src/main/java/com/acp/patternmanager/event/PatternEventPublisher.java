package com.acp.patternmanager.event;

import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.PatternApprovedEvent;
import com.acp.eventmodel.generated.PatternDiscoveredEvent;
import com.acp.eventmodel.generated.SessionWindow;
import com.acp.eventmodel.generated.Timing__1;
import com.acp.patternmanager.config.KafkaTopicProperties;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The SOLE producer of {@code PatternDiscoveredEvent} ({@code patterns.discovered}) and
 * {@code PatternApprovedEvent} ({@code patterns.approved}). Both events carry the {@code sessionWindow}
 * read from the PERSISTED Pattern Store record (so the approved event's window is byte-identical to
 * the value first emitted at discovery — criterion 20). {@link EventCodec#serialize} validates every
 * event (incl. {@code sessionWindow} and the {@code additionalProperties:false} rule that keeps the
 * internal structural-validation flag off the wire) before it is sent.
 */
@Component
public class PatternEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PatternEventPublisher.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final String SOURCE = "pattern-manager";

    private final EventCodec codec;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topics;
    private final ObjectMapper objectMapper;

    public PatternEventPublisher(EventCodec codec, KafkaTemplate<String, String> patternKafkaTemplate,
            KafkaTopicProperties topics, ObjectMapper objectMapper) {
        this.codec = codec;
        this.kafkaTemplate = patternKafkaTemplate;
        this.topics = topics;
        this.objectMapper = objectMapper;
    }

    /**
     * Emit one {@code PatternDiscoveredEvent} ({@code lifecycle = draft}) for a newly persisted pattern.
     *
     * @param entity the persisted pattern (source of every field, incl. sessionWindow)
     * @param traceId the trace id propagated from the originating mined event
     */
    public void publishDiscovered(PatternEntity entity, String traceId) {
        PatternDiscoveredEvent payload = new PatternDiscoveredEvent();
        payload.setPatternId(entity.getPatternId().toString());
        payload.setSequence(sequence(entity));
        payload.setRootCauseAlarmType(entity.getRootCauseAlarmType());
        payload.setSupport(entity.getSupport());
        payload.setConfidence(entity.getConfidence());
        payload.setLift(entity.getLift());
        payload.setTiming(timing(entity));
        payload.setSessionWindow(sessionWindow(entity));
        payload.setCodebookMatchId(entity.getCodebookMatchId());
        payload.setLifecycle("draft");

        String json = codec.serialize(envelope("PatternDiscoveredEvent", payload, traceId));
        kafkaTemplate.send(topics.discovered(), entity.getPatternId().toString(), json);
        log.info("emitted PatternDiscoveredEvent patternId={} windowMs={} type={}",
                entity.getPatternId(), entity.getSessionWindowMs(), entity.getSessionWindowType());
    }

    /**
     * Emit one {@code PatternApprovedEvent} ({@code lifecycle = approved}) for an approval transition.
     * The {@code sessionWindow} is read from the persisted record — identical to the discovered value.
     *
     * @param entity the approved pattern
     * @param traceId the trace id for this API-initiated approval
     */
    public void publishApproved(PatternEntity entity, String traceId) {
        PatternApprovedEvent payload = new PatternApprovedEvent();
        payload.setPatternId(entity.getPatternId().toString());
        payload.setSequence(sequence(entity));
        payload.setRootCauseAlarmType(entity.getRootCauseAlarmType());
        payload.setSupport(entity.getSupport());
        payload.setConfidence(entity.getConfidence());
        payload.setLift(entity.getLift());
        payload.setTiming(approvedTiming(entity));
        payload.setSessionWindow(sessionWindow(entity));
        payload.setCodebookMatchId(entity.getCodebookMatchId());
        payload.setLifecycle("approved");

        String json = codec.serialize(envelope("PatternApprovedEvent", payload, traceId));
        kafkaTemplate.send(topics.approved(), entity.getPatternId().toString(), json);
        log.info("emitted PatternApprovedEvent patternId={} windowMs={} type={}",
                entity.getPatternId(), entity.getSessionWindowMs(), entity.getSessionWindowType());
    }

    private TypedEnvelope<Object> envelope(String type, Object payload, String traceId) {
        return new TypedEnvelope<>(
                UUID.randomUUID().toString(),
                type,
                1,
                OffsetDateTime.now(ZoneOffset.UTC).format(ISO),
                SOURCE,
                traceId != null ? traceId : UUID.randomUUID().toString(),
                payload);
    }

    private List<String> sequence(PatternEntity entity) {
        return entity.getSequenceElements().stream()
                .map(com.acp.patternmanager.store.entity.SequenceElementEntity::getAlarmType)
                .toList();
    }

    private SessionWindow sessionWindow(PatternEntity entity) {
        SessionWindow sw = new SessionWindow();
        sw.setWindowMs((int) entity.getSessionWindowMs());
        sw.setType(SessionWindow.Type.fromValue(entity.getSessionWindowType()));
        return sw;
    }

    private Timing__1 timing(PatternEntity entity) {
        Timing__1 timing = new Timing__1();
        readTiming(entity).forEach(timing::setAdditionalProperty);
        return timing;
    }

    /** PatternApprovedEvent uses its own Timing type; reuse the map-population approach. */
    private com.acp.eventmodel.generated.Timing approvedTiming(PatternEntity entity) {
        com.acp.eventmodel.generated.Timing timing = new com.acp.eventmodel.generated.Timing();
        readTiming(entity).forEach(timing::setAdditionalProperty);
        return timing;
    }

    private Map<String, Object> readTiming(PatternEntity entity) {
        try {
            String json = entity.getTimingJson();
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read persisted timing JSON", e);
        }
    }
}
