package com.acp.correlationengine.integration;

import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.correlationengine.pattern.PatternRefreshService;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.PatternApprovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;

/**
 * Consumes {@code patterns.approved} ({@code PatternApprovedEvent}). The event is a REFRESH TRIGGER
 * only — on each event the engine re-fetches the approved pattern set from the Pattern Manager read
 * API (the source of {@code trailId} for {@code (trailId, patternId)} keying; the frozen event
 * carries no {@code trailId} — AC27). Deduped on {@code eventId}. Poison messages go to
 * {@code patterns.approved.dlq} (AC19).
 */
public class PatternApprovedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PatternApprovedConsumer.class);

    private final PatternRefreshService refreshService;
    private final CompatibilityIndexService indexService;
    private final ProcessedEventStore processedEvents;
    private final EventCodec codec;
    private final DlqProducer dlq;
    private final String topic;

    public PatternApprovedConsumer(PatternRefreshService refreshService,
            CompatibilityIndexService indexService, ProcessedEventStore processedEvents,
            EventCodec codec, DlqProducer dlq, CorrelationEngineProperties props) {
        this.refreshService = refreshService;
        this.indexService = indexService;
        this.processedEvents = processedEvents;
        this.codec = codec;
        this.dlq = dlq;
        this.topic = props.topics().patternsApproved();
    }

    @KafkaListener(
            topics = "${correlation-engine.topics.patterns-approved}",
            groupId = "correlation-engine-patterns")
    public void onMessage(@Payload String raw) {
        TypedEnvelope<Object> envelope;
        try {
            envelope = codec.deserialize(raw);
            if (!(envelope.getPayload() instanceof PatternApprovedEvent)) {
                dlq.route(topic, null, raw,
                        new IllegalArgumentException("expected PatternApprovedEvent, got "
                                + envelope.getType()));
                return;
            }
        } catch (RuntimeException e) {
            dlq.route(topic, null, raw, e);
            return;
        }
        if (!processedEvents.markIfNew("patterns.approved", envelope.getEventId())) {
            return; // redelivered event — idempotent no-op
        }
        refreshService.refreshOnApproval();
        // Compute the compatible-trail set for the (re)approved patterns BEFORE ack, so a newly
        // approved pattern is never matchable before its compatible set exists (spec Refresh
        // ordering / AC38).
        indexService.rebuildForApprovedSet();
        log.debug("patterns.approved {} handled — pattern set refreshed + index updated",
                envelope.getEventId());
    }
}
