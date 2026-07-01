package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A supporting example instance for a pattern — an occurrence reference sourced from the Pattern
 * Miner's provenance. May be zero rows when provenance carries no occurrences.
 */
@Entity
@Table(name = "supporting_instance", schema = "pattern")
public class SupportingInstanceEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "pattern_id", nullable = false)
    private PatternEntity pattern;

    @Column(name = "source_window_id")
    private String sourceWindowId;

    @Column(name = "snapshot_id")
    private String snapshotId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "occurrence", columnDefinition = "jsonb")
    private String occurrence;

    protected SupportingInstanceEntity() {
    }

    public SupportingInstanceEntity(UUID id, PatternEntity pattern, String sourceWindowId,
            String snapshotId, String occurrence) {
        this.id = id;
        this.pattern = pattern;
        this.sourceWindowId = sourceWindowId;
        this.snapshotId = snapshotId;
        this.occurrence = occurrence;
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

    public String getSourceWindowId() {
        return sourceWindowId;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public String getOccurrence() {
        return occurrence;
    }
}
