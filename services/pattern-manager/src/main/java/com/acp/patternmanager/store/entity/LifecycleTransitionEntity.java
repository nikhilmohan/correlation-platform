package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An auditable lifecycle transition — one row per state change (draft->approved, draft->rejected,
 * draft->deprecated, approved->deprecated), each with a non-null {@code transitioned_at}.
 */
@Entity
@Table(name = "lifecycle_transition", schema = "pattern")
public class LifecycleTransitionEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "pattern_id", nullable = false)
    private UUID patternId;

    @Column(name = "from_state", nullable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false)
    private String toState;

    @Column(name = "reviewer")
    private String reviewer;

    @Column(name = "notes")
    private String notes;

    @Column(name = "transitioned_at", nullable = false)
    private OffsetDateTime transitionedAt;

    protected LifecycleTransitionEntity() {
    }

    public LifecycleTransitionEntity(UUID id, UUID patternId, String fromState, String toState,
            String reviewer, String notes, OffsetDateTime transitionedAt) {
        this.id = id;
        this.patternId = patternId;
        this.fromState = fromState;
        this.toState = toState;
        this.reviewer = reviewer;
        this.notes = notes;
        this.transitionedAt = transitionedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPatternId() {
        return patternId;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public String getReviewer() {
        return reviewer;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getTransitionedAt() {
        return transitionedAt;
    }
}
