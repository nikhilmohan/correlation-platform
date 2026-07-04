package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * [ANCHOR-CONSOL] One row per mined {@code eventId} that has been folded into an anchored pattern.
 * The {@code event_id} PRIMARY KEY is the fold idempotency guard: the consolidation does an
 * {@code INSERT ... ON CONFLICT (event_id) DO NOTHING} BEFORE aggregating, so a re-delivered or
 * replayed mined event whose {@code eventId} is already present is not folded again (no
 * double-count) — complementing the {@code processed_event} gate. Because occurrences are summed
 * over this DISTINCT set and each {@code eventId} contributes at most once, the aggregate is a
 * deterministic function of the contributing-event set, independent of arrival order.
 */
@Entity
@Table(name = "contributing_event", schema = "pattern")
public class ContributingEventEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "pattern_id", nullable = false)
    private UUID patternId;

    @Column(name = "anchor_scenario_id")
    private String anchorScenarioId;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    @Column(name = "support", nullable = false)
    private double support;

    @Column(name = "folded_at", nullable = false)
    private OffsetDateTime foldedAt;

    protected ContributingEventEntity() {
    }

    public ContributingEventEntity(UUID eventId, UUID patternId, String anchorScenarioId,
            int occurrences, double support, OffsetDateTime foldedAt) {
        this.eventId = eventId;
        this.patternId = patternId;
        this.anchorScenarioId = anchorScenarioId;
        this.occurrences = occurrences;
        this.support = support;
        this.foldedAt = foldedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getPatternId() {
        return patternId;
    }

    public String getAnchorScenarioId() {
        return anchorScenarioId;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public double getSupport() {
        return support;
    }

    public OffsetDateTime getFoldedAt() {
        return foldedAt;
    }
}
