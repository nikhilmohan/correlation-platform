package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The deterministic patternId (UUIDv5) underpins consume-and-persist idempotency (criterion 10). */
class UuidV5Test {

    @Test
    void sameNameProducesSameUuid() {
        String name = "trail-1|LOS,LinkDown|w1|s1";
        assertThat(UuidV5.from(name)).isEqualTo(UuidV5.from(name));
    }

    @Test
    void differentNamesProduceDifferentUuids() {
        assertThat(UuidV5.from("a")).isNotEqualTo(UuidV5.from("b"));
    }

    @Test
    void isVersion5() {
        UUID id = UuidV5.from("trail-1|LOS,LinkDown|w1|s1");
        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2); // RFC 4122
    }

    // --- [ANCHOR-CONSOL] anchor-identity + per-event identity ---

    @Test
    void anchorIdentityIsDeterministicAcrossSubRuns() {
        // Same (domain, snapshotId, codebookVersion, anchorScenarioId) always yields the same id, so
        // all sub-runs of one fault-origin map to ONE Pattern Store row (AC-C1).
        UUID a = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        UUID b = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        assertThat(a).isEqualTo(b);
        assertThat(a.version()).isEqualTo(5);
    }

    @Test
    void anchorIdentityRemintsAcrossSnapshotOrCodebook() {
        // Different snapshot OR codebook version -> different identity (AC-C5): fault models from
        // different contexts are not merged.
        UUID base = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        assertThat(UuidV5.anchorIdentity("core-ip", "snap-2", "cb-1", "SC-FIBER")).isNotEqualTo(base);
        assertThat(UuidV5.anchorIdentity("core-ip", "snap-1", "cb-2", "SC-FIBER")).isNotEqualTo(base);
        assertThat(UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-OTHER")).isNotEqualTo(base);
    }

    @Test
    void perEventIdentityIsDistinctPerMinedEvent() {
        // Unexplained patterns keep a per-event identity (different sourceWindowId -> different id).
        UUID a = UuidV5.perEventIdentity("trail-1", List.of("LOS", "LinkDown"), "w1", "snap-1");
        UUID b = UuidV5.perEventIdentity("trail-1", List.of("LOS", "LinkDown"), "w2", "snap-1");
        assertThat(a).isNotEqualTo(b);
        assertThat(UuidV5.perEventIdentity("trail-1", List.of("LOS", "LinkDown"), "w1", "snap-1"))
                .isEqualTo(a);
    }

    @Test
    void anchorAndPerEventIdentitiesDoNotCollide() {
        UUID anchored = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        UUID perEvent = UuidV5.perEventIdentity("trail-1", List.of("LOS"), "w1", "snap-1");
        assertThat(anchored).isNotEqualTo(perEvent);
    }
}
