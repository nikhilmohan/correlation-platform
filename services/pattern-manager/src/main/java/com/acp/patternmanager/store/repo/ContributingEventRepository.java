package com.acp.patternmanager.store.repo;

import com.acp.patternmanager.store.entity.ContributingEventEntity;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * [ANCHOR-CONSOL] Repository for the {@code contributing_event} fold-guard set.
 *
 * <p>{@link #insertIgnoreConflict} performs the atomic idempotency guard: a native
 * {@code INSERT ... ON CONFLICT (event_id) DO NOTHING}. It returns the number of rows actually
 * inserted (1 = this {@code eventId} is a NEW contributor and must be folded; 0 = already present,
 * a replay — the fold must be a no-op). Doing the guard as one SQL statement (rather than
 * exists-then-insert) keeps it race-safe under the row lock the consolidation holds on the pattern.
 */
public interface ContributingEventRepository extends JpaRepository<ContributingEventEntity, UUID> {

    /**
     * Atomically record a contributing event, ignoring a conflicting {@code event_id}.
     *
     * @return 1 if this {@code eventId} was newly inserted (fold it), 0 if already present (no-op).
     */
    @Modifying
    @Query(value = """
            INSERT INTO pattern.contributing_event
                (event_id, pattern_id, anchor_scenario_id, occurrences, support, folded_at)
            VALUES (:eventId, :patternId, :anchorScenarioId, :occurrences, :support, :foldedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("eventId") UUID eventId,
            @Param("patternId") UUID patternId,
            @Param("anchorScenarioId") String anchorScenarioId,
            @Param("occurrences") int occurrences,
            @Param("support") double support,
            @Param("foldedAt") OffsetDateTime foldedAt);

    boolean existsByEventId(UUID eventId);

    long countByPatternId(UUID patternId);
}
