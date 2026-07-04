package com.acp.patternmanager.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * [SIG-FOLD] One row per DISTINCT {@code trailId} that has contributed to a pattern — the source of
 * truth for {@code trailCount} (the cross-trail spread metric). The composite PK
 * {@code (pattern_id, trail_id)} makes the fold's {@code INSERT ... ON CONFLICT (pattern_id, trail_id)
 * DO NOTHING} record each trail at most once, so the pattern's {@code trail_count} counter stays
 * exactly equal to the distinct trail count and is idempotent under replay / repeated trails.
 */
@Entity
@Table(name = "pattern_trail", schema = "pattern")
@IdClass(PatternTrailEntity.PatternTrailId.class)
public class PatternTrailEntity {

    @Id
    @Column(name = "pattern_id")
    private UUID patternId;

    @Id
    @Column(name = "trail_id")
    private String trailId;

    @Column(name = "first_seen", nullable = false)
    private OffsetDateTime firstSeen;

    protected PatternTrailEntity() {
    }

    public PatternTrailEntity(UUID patternId, String trailId, OffsetDateTime firstSeen) {
        this.patternId = patternId;
        this.trailId = trailId;
        this.firstSeen = firstSeen;
    }

    public UUID getPatternId() {
        return patternId;
    }

    public String getTrailId() {
        return trailId;
    }

    public OffsetDateTime getFirstSeen() {
        return firstSeen;
    }

    /** Composite-key class for {@link PatternTrailEntity}. */
    public static class PatternTrailId implements Serializable {
        private UUID patternId;
        private String trailId;

        public PatternTrailId() {
        }

        public PatternTrailId(UUID patternId, String trailId) {
            this.patternId = patternId;
            this.trailId = trailId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PatternTrailId that)) {
                return false;
            }
            return Objects.equals(patternId, that.patternId) && Objects.equals(trailId, that.trailId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(patternId, trailId);
        }
    }
}
