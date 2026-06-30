package com.acp.enrichment.kafka;

import com.acp.enrichment.pipeline.EnrichmentPipeline;
import com.acp.enrichment.pipeline.Path;
import com.acp.eventmodel.CodecException;
import com.acp.eventmodel.SchemaVersionException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Two {@link KafkaListener} methods — the history listener ({@code alarms.history}, Path HISTORY)
 * and the live listener ({@code alarms.live}, Path LIVE) — both driving the single shared
 * {@link EnrichmentPipeline} (criterion 9). Each parses the envelope, dedupes on {@code eventId},
 * propagates {@code traceId} into the log MDC, and on a {@link CodecException} /
 * {@link SchemaVersionException} routes the raw message to the matching DLQ and continues (criterion
 * 15). At-least-once: the offset is acknowledged after the message is handled (success, drop, or
 * DLQ) so a poison message never blocks the partition.
 */
@Component
public class AlarmConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmConsumer.class);

    private final EnvelopeParser parser;
    private final EnrichmentPipeline pipeline;
    private final DlqRouter dlqRouter;
    private final EventIdDedupe dedupe;
    private final MeterRegistry meters;

    public AlarmConsumer(EnvelopeParser parser, EnrichmentPipeline pipeline, DlqRouter dlqRouter,
            EventIdDedupe dedupe, MeterRegistry meters) {
        this.parser = parser;
        this.pipeline = pipeline;
        this.dlqRouter = dlqRouter;
        this.dedupe = dedupe;
        this.meters = meters;
    }

    @KafkaListener(topics = "${enrichment.history-topic:alarms.history}",
            groupId = "${enrichment.consumer-group:enrichment}")
    public void onHistory(String value, Acknowledgment ack) {
        handle(value, Path.HISTORY, ack);
    }

    @KafkaListener(topics = "${enrichment.live-topic:alarms.live}",
            groupId = "${enrichment.consumer-group:enrichment}")
    public void onLive(String value, Acknowledgment ack) {
        handle(value, Path.LIVE, ack);
    }

    private void handle(String value, Path path, Acknowledgment ack) {
        try {
            RawEnvelope env;
            try {
                env = parser.parse(value);
            } catch (SchemaVersionException e) {
                dlqRouter.route(path, value, "schema_version", e.getMessage());
                return;
            } catch (CodecException e) {
                dlqRouter.route(path, value, "deserialize", e.getMessage());
                return;
            }

            if (!dedupe.firstSeen(env.eventId())) {
                meters.counter("alarms_deduped_eventid_total", "path", path.name()).increment();
                return;
            }

            MDC.put("traceId", env.traceId() == null ? "" : env.traceId());
            try {
                pipeline.process(env.payload(), env.source(), env.occurredAt(), env.traceId(),
                        path, value);
            } finally {
                MDC.remove("traceId");
            }
        } catch (RuntimeException e) {
            // Unexpected: never lose the message — DLQ it and keep the partition moving.
            log.error("unexpected error handling message on path {}: {}", path, e.getMessage(), e);
            dlqRouter.route(path, value, "unexpected", e.getMessage());
        } finally {
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }
}
