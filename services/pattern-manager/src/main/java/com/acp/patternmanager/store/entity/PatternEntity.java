package com.acp.patternmanager.store.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The Pattern Store {@code pattern.pattern} row — the enriched, governed pattern record. Sole owner
 * is the Pattern Manager. {@code patternId} is a deterministic UUIDv5 over the mining provenance
 * (upsert idempotency). Carries the internal structural-validation flag/reason + edit metadata
 * (never on the frozen events) AND the derived {@code sessionWindow} (which IS on the frozen events).
 */
@Entity
@Table(name = "pattern", schema = "pattern")
public class PatternEntity {

    @Id
    @Column(name = "pattern_id")
    private UUID patternId;

    @Column(name = "trail_id", nullable = false)
    private String trailId;

    @Column(name = "root_cause_alarm_type", nullable = false)
    private String rootCauseAlarmType;

    @Column(name = "support", nullable = false)
    private double support;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "lift", nullable = false)
    private double lift;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timing", nullable = false, columnDefinition = "jsonb")
    private String timingJson;

    @Column(name = "codebook_match_id")
    private String codebookMatchId;

    @Column(name = "reconcile_status", nullable = false)
    private String reconcileStatus;

    @Column(name = "structurally_validated", nullable = false)
    private boolean structurallyValidated;

    @Column(name = "structural_validation_reason")
    private String structuralValidationReason;

    @Column(name = "session_window_ms", nullable = false)
    private long sessionWindowMs;

    @Column(name = "session_window_type", nullable = false)
    private String sessionWindowType;

    @Column(name = "instance_count", nullable = false)
    private int instanceCount;

    @Column(name = "lifecycle", nullable = false)
    private String lifecycle;

    @Column(name = "domain")
    private String domain;

    // [ANCHOR-CONSOL] Anchor-identity columns. anchorScenarioId is null for unexplained patterns
    // (per-event identity, never consolidated). snapshotId + codebookVersion scope the identity so a
    // new topology snapshot / recompiled codebook re-mints it (a different fault context).
    @Column(name = "anchor_scenario_id")
    private String anchorScenarioId;

    @Column(name = "snapshot_id")
    private String snapshotId;

    @Column(name = "codebook_version")
    private String codebookVersion;

    // [ANCHOR-CONSOL] Occurrence-weighted support (support * occurrences) of the contributor that
    // currently owns the representative sequence; used only to break the representative tie on a
    // later fold. Internal — never served on API/events.
    @Column(name = "representative_weight")
    private Double representativeWeight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edit_meta", columnDefinition = "jsonb")
    private String editMeta;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<SequenceElementEntity> sequenceElements = new ArrayList<>();

    @OneToMany(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<SupportingInstanceEntity> supportingInstances = new ArrayList<>();

    // [SAMPLE-ALARMS] The bounded, representative sample of real member alarms (operator XAI). Written
    // ONLY at draft creation (createDraftRow) with the first contributor's sample; the consolidation
    // fold never touches this collection, so it stays deterministic + bounded across folds. Ordered by
    // the miner's received position for a deterministic serve order.
    @OneToMany(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<SampleAlarmEntity> sampleAlarms = new ArrayList<>();

    public PatternEntity() {
    }

    // --- getters / setters ---

    public UUID getPatternId() {
        return patternId;
    }

    public void setPatternId(UUID patternId) {
        this.patternId = patternId;
    }

    public String getTrailId() {
        return trailId;
    }

    public void setTrailId(String trailId) {
        this.trailId = trailId;
    }

    public String getRootCauseAlarmType() {
        return rootCauseAlarmType;
    }

    public void setRootCauseAlarmType(String rootCauseAlarmType) {
        this.rootCauseAlarmType = rootCauseAlarmType;
    }

    public double getSupport() {
        return support;
    }

    public void setSupport(double support) {
        this.support = support;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public double getLift() {
        return lift;
    }

    public void setLift(double lift) {
        this.lift = lift;
    }

    public String getTimingJson() {
        return timingJson;
    }

    public void setTimingJson(String timingJson) {
        this.timingJson = timingJson;
    }

    public String getCodebookMatchId() {
        return codebookMatchId;
    }

    public void setCodebookMatchId(String codebookMatchId) {
        this.codebookMatchId = codebookMatchId;
    }

    public String getReconcileStatus() {
        return reconcileStatus;
    }

    public void setReconcileStatus(String reconcileStatus) {
        this.reconcileStatus = reconcileStatus;
    }

    public boolean isStructurallyValidated() {
        return structurallyValidated;
    }

    public void setStructurallyValidated(boolean structurallyValidated) {
        this.structurallyValidated = structurallyValidated;
    }

    public String getStructuralValidationReason() {
        return structuralValidationReason;
    }

    public void setStructuralValidationReason(String structuralValidationReason) {
        this.structuralValidationReason = structuralValidationReason;
    }

    public long getSessionWindowMs() {
        return sessionWindowMs;
    }

    public void setSessionWindowMs(long sessionWindowMs) {
        this.sessionWindowMs = sessionWindowMs;
    }

    public String getSessionWindowType() {
        return sessionWindowType;
    }

    public void setSessionWindowType(String sessionWindowType) {
        this.sessionWindowType = sessionWindowType;
    }

    public int getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(int instanceCount) {
        this.instanceCount = instanceCount;
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(String lifecycle) {
        this.lifecycle = lifecycle;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getAnchorScenarioId() {
        return anchorScenarioId;
    }

    public void setAnchorScenarioId(String anchorScenarioId) {
        this.anchorScenarioId = anchorScenarioId;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getCodebookVersion() {
        return codebookVersion;
    }

    public void setCodebookVersion(String codebookVersion) {
        this.codebookVersion = codebookVersion;
    }

    public Double getRepresentativeWeight() {
        return representativeWeight;
    }

    public void setRepresentativeWeight(Double representativeWeight) {
        this.representativeWeight = representativeWeight;
    }

    public String getEditMeta() {
        return editMeta;
    }

    public void setEditMeta(String editMeta) {
        this.editMeta = editMeta;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<SequenceElementEntity> getSequenceElements() {
        return sequenceElements;
    }

    public void setSequenceElements(List<SequenceElementEntity> sequenceElements) {
        this.sequenceElements = sequenceElements;
    }

    public List<SupportingInstanceEntity> getSupportingInstances() {
        return supportingInstances;
    }

    public void setSupportingInstances(List<SupportingInstanceEntity> supportingInstances) {
        this.supportingInstances = supportingInstances;
    }

    public List<SampleAlarmEntity> getSampleAlarms() {
        return sampleAlarms;
    }

    public void setSampleAlarms(List<SampleAlarmEntity> sampleAlarms) {
        this.sampleAlarms = sampleAlarms;
    }
}
