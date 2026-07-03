package com.acp.patternmanager.store.repo;

import com.acp.patternmanager.store.entity.PatternTrailEntity;
import com.acp.patternmanager.store.entity.PatternTrailEntity.PatternTrailId;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * [SIG-FOLD] Repository for the {@code pattern.pattern_trail} distinct-trail set.
 *
 * <p>{@link #insertIgnoreConflict} records a contributing {@code trailId} for a pattern with an atomic
 * {@code INSERT ... ON CONFLICT (pattern_id, trail_id) DO NOTHING}. It returns the number of rows
 * actually inserted — 1 iff the trail is genuinely NEW for this pattern (so the caller bumps
 * {@code trail_count} by exactly that), 0 if the trail was already recorded (a second occurrence on
 * the same trail). This keeps {@code trail_count} equal to the DISTINCT trail count and idempotent.
 */
public interface PatternTrailRepository extends JpaRepository<PatternTrailEntity, PatternTrailId> {

    /**
     * Atomically record a contributing trail, ignoring a conflicting {@code (pattern_id, trail_id)}.
     *
     * @return 1 if this trail was newly inserted for the pattern (bump {@code trail_count}), else 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO pattern.pattern_trail (pattern_id, trail_id, first_seen)
            VALUES (:patternId, :trailId, :firstSeen)
            ON CONFLICT (pattern_id, trail_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("patternId") UUID patternId,
            @Param("trailId") String trailId,
            @Param("firstSeen") OffsetDateTime firstSeen);

    long countByPatternId(UUID patternId);
}
