package com.acp.correlationengine.integration;

import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.eventmodel.EventCodec;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.TrailsBuiltEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;

/**
 * NEW consumer on the EXISTING {@code trails.built} topic (spec OQ-G4 resolved — a new consumer on an
 * existing topic, NOT a new topic/payload/field, so no contract change). On each new
 * {@code TrailsBuiltEvent} the Correlation Engine rebuilds the compatibility index for the new
 * snapshot's trail catalog so approved patterns re-generalize against the current topology (spec
 * Task 1b, AC37). The event is a TRIGGER only — {@code rebuildAll()} authoritatively re-enumerates
 * trails via {@code GET /trails?snapshotId&domain}; the payload's {@code trailIds[]} is a hint.
 *
 * <p>The rebuild runs synchronously in the listener BEFORE the offset is committed (ack-mode record),
 * so alarms are not dispatched against a stale catalog. Deduped on {@code eventId}; poison messages go
 * to {@code trails.built.dlq} (never dropped). Consumer group id follows the platform
 * {@code "<service>-<topic>"} convention: {@code correlation-engine-trails.built}.
 */
public class TrailsBuiltConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrailsBuiltConsumer.class);

    static final String TOPIC = "trails.built";
    static final String SCOPE = "trails.built";

    private final CompatibilityIndexService indexService;
    private final ProcessedEventStore processedEvents;
    private final EventCodec codec;
    private final DlqProducer dlq;

    public TrailsBuiltConsumer(CompatibilityIndexService indexService,
            ProcessedEventStore processedEvents, EventCodec codec, DlqProducer dlq) {
        this.indexService = indexService;
        this.processedEvents = processedEvents;
        this.codec = codec;
        this.dlq = dlq;
    }

    @KafkaListener(
            topics = "${correlation-engine.topics.trails-built:trails.built}",
            groupId = "correlation-engine-trails.built")
    public void onMessage(@Payload String raw) {
        TypedEnvelope<Object> envelope;
        TrailsBuiltEvent event;
        try {
            envelope = codec.deserialize(raw);
            if (!(envelope.getPayload() instanceof TrailsBuiltEvent parsed)) {
                dlq.route(TOPIC, null, raw,
                        new IllegalArgumentException("expected TrailsBuiltEvent, got "
                                + envelope.getType()));
                return;
            }
            event = parsed;
        } catch (RuntimeException e) {
            dlq.route(TOPIC, null, raw, e);
            return;
        }
        if (!processedEvents.markIfNew(SCOPE, envelope.getEventId())) {
            return; // redelivered event — idempotent no-op (rebuild is idempotent anyway)
        }
        indexService.noteSnapshot(event.getSnapshotId());
        indexService.rebuildAll(event.getSnapshotId(), event.getDomain());
        log.info("trails.built {} handled — compatibility index rebuilt for snapshot {}",
                envelope.getEventId(), event.getSnapshotId());
    }
}
