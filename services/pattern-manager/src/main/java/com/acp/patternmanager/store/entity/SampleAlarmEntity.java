package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A bounded, representative sample of a real member alarm a pattern was mined from — operator XAI /
 * trust evidence (spec-sample-alarms AC-SA-1..7). One-to-many child of {@link PatternEntity}, mirroring
 * {@link SupportingInstanceEntity} (surrogate {@code id} PK, {@code pattern_id} FK). Written ONLY at
 * draft creation (the first/creating contributor's sample); the consolidation fold never touches this
 * collection, so it stays deterministic + bounded + replay-safe. {@code position} preserves the miner's
 * received order for a deterministic serve order.
 */
@Entity
@Table(name = "sample_alarm", schema = "pattern")
public class SampleAlarmEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "pattern_id", nullable = false)
    private PatternEntity pattern;

    @Column(name = "alarm_id", nullable = false)
    private String alarmId;

    @Column(name = "alarm_type", nullable = false)
    private String alarmType;

    @Column(name = "raised_at", nullable = false)
    private OffsetDateTime raisedAt;

    @Column(name = "managed_object_id", nullable = false)
    private String managedObjectId;

    @Column(name = "perceived_severity", nullable = false)
    private String perceivedSeverity;

    @Column(name = "position", nullable = false)
    private int position;

    protected SampleAlarmEntity() {
    }

    public SampleAlarmEntity(UUID id, PatternEntity pattern, String alarmId, String alarmType,
            OffsetDateTime raisedAt, String managedObjectId, String perceivedSeverity, int position) {
        this.id = id;
        this.pattern = pattern;
        this.alarmId = alarmId;
        this.alarmType = alarmType;
        this.raisedAt = raisedAt;
        this.managedObjectId = managedObjectId;
        this.perceivedSeverity = perceivedSeverity;
        this.position = position;
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

    public String getAlarmId() {
        return alarmId;
    }

    public String getAlarmType() {
        return alarmType;
    }

    public OffsetDateTime getRaisedAt() {
        return raisedAt;
    }

    public String getManagedObjectId() {
        return managedObjectId;
    }

    public String getPerceivedSeverity() {
        return perceivedSeverity;
    }

    public int getPosition() {
        return position;
    }
}
