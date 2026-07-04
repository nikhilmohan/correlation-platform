package com.acp.correlationengine.integration;

import com.acp.correlationengine.codebook.CodebookRefreshService;
import com.acp.correlationengine.config.CorrelationEngineProperties;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.CodebookGeneratedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;

/**
 * Consumes {@code codebook.generated} ({@code CodebookGeneratedEvent}): fetches the per-trail
 * signatures for the new {@code codebookId} and installs them latest-in-scope per
 * {@code (snapshotId, trailId)} (AC20). Deduped on {@code eventId}. Poison messages go to
 * {@code codebook.generated.dlq} (AC19).
 */
public class CodebookConsumer {

    private static final Logger log = LoggerFactory.getLogger(CodebookConsumer.class);

    private final CodebookRefreshService refreshService;
    private final ProcessedEventStore processedEvents;
    private final EventCodec codec;
    private final DlqProducer dlq;
    private final String topic;

    public CodebookConsumer(CodebookRefreshService refreshService,
            ProcessedEventStore processedEvents, EventCodec codec, DlqProducer dlq,
            CorrelationEngineProperties props) {
        this.refreshService = refreshService;
        this.processedEvents = processedEvents;
        this.codec = codec;
        this.dlq = dlq;
        this.topic = props.topics().codebookGenerated();
    }

    @KafkaListener(
            topics = "${correlation-engine.topics.codebook-generated}",
            groupId = "correlation-engine-codebook")
    public void onMessage(@Payload String raw) {
        TypedEnvelope<Object> envelope;
        CodebookGeneratedEvent event;
        try {
            envelope = codec.deserialize(raw);
            if (!(envelope.getPayload() instanceof CodebookGeneratedEvent parsed)) {
                dlq.route(topic, null, raw,
                        new IllegalArgumentException("expected CodebookGeneratedEvent, got "
                                + envelope.getType()));
                return;
            }
            event = parsed;
        } catch (RuntimeException e) {
            dlq.route(topic, null, raw, e);
            return;
        }
        if (!processedEvents.markIfNew("codebook.generated", envelope.getEventId())) {
            return; // redelivered event — idempotent no-op
        }
        refreshService.onCodebookGenerated(event.getCodebookId(), event.getSnapshotId());
        log.debug("codebook.generated {} handled — codebook {} installed",
                envelope.getEventId(), event.getCodebookId());
    }
}
