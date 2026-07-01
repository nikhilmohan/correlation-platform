package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * An ordered element of a pattern's alarm-type sequence. Carries the {@code optional} marker set by
 * the operator-edit placeholder (internal; never on the frozen events). {@code UNIQUE (pattern_id,
 * position)} orders the sequence for event reconstruction.
 */
@Entity
@Table(name = "sequence_element", schema = "pattern")
public class SequenceElementEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "pattern_id", nullable = false)
    private PatternEntity pattern;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "alarm_type", nullable = false)
    private String alarmType;

    @Column(name = "optional", nullable = false)
    private boolean optional;

    protected SequenceElementEntity() {
    }

    public SequenceElementEntity(UUID id, PatternEntity pattern, int position, String alarmType,
            boolean optional) {
        this.id = id;
        this.pattern = pattern;
        this.position = position;
        this.alarmType = alarmType;
        this.optional = optional;
    }

    public UUID getId() {
        return id;
    }

    public PatternEntity getPattern() {
        return pattern;
    }

    public void setPattern(PatternEntity pattern) {
        this.pattern = pattern;
    }

    public int getPosition() {
        return position;
    }

    public String getAlarmType() {
        return alarmType;
    }

    public boolean isOptional() {
        return optional;
    }

    public void setOptional(boolean optional) {
        this.optional = optional;
    }
}
