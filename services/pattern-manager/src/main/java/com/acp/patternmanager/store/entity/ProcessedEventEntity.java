package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The idempotency dedupe set — one row per consumed {@code eventId}. Written in the same DB
 * transaction as the pattern upsert so a redelivered {@code eventId} (Kafka at-least-once) is a
 * no-op (criterion 10).
 */
@Entity
@Table(name = "processed_event", schema = "pattern")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "source")
    private String source;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;

    @Column(name = "pattern_id")
    private UUID patternId;

    protected ProcessedEventEntity() {
    }

    public ProcessedEventEntity(UUID eventId, String source, OffsetDateTime processedAt,
            UUID patternId) {
        this.eventId = eventId;
        this.source = source;
        this.processedAt = processedAt;
        this.patternId = patternId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getPatternId() {
        return patternId;
    }
}
