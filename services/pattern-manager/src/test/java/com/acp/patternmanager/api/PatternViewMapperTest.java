package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [SIG-FOLD] AC-SF-16 (unit half): the mapper carries the four impact-metric fields onto PatternView. */
class PatternViewMapperTest {

    private final PatternViewMapper mapper = new PatternViewMapper(new ObjectMapper());

    @Test
    void mapsImpactFields() {
        OffsetDateTime first = OffsetDateTime.parse("2026-07-01T10:00:00Z");
        OffsetDateTime last = OffsetDateTime.parse("2026-07-03T12:00:00Z");
        PatternEntity e = new PatternEntity();
        e.setPatternId(UUID.randomUUID());
        e.setTrailId("trail-1");
        e.setRootCauseAlarmType("LOS");
        e.setSupport(0.5);
        e.setConfidence(0.7);
        e.setLift(2.0);
        e.setTimingJson("{}");
        e.setReconcileStatus("unexplained");
        e.setStructurallyValidated(true);
        e.setSessionWindowMs(20_000);
        e.setSessionWindowType("gap-based");
        e.setInstanceCount(30);
        e.setOccurrenceCount(12);
        e.setTrailCount(11);
        e.setFirstSeen(first);
        e.setLastSeen(last);
        e.setLifecycle("draft");
        e.setDomain("core-ip");
        e.setCreatedAt(first);
        e.setUpdatedAt(last);

        PatternView view = mapper.toView(e);

        assertThat(view.instanceCount()).isEqualTo(30);
        assertThat(view.occurrenceCount()).isEqualTo(12);
        assertThat(view.trailCount()).isEqualTo(11);
        assertThat(view.firstSeen()).isEqualTo(first);
        assertThat(view.lastSeen()).isEqualTo(last);
    }
}
