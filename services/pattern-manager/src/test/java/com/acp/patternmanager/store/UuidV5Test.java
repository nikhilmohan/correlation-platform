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

    // --- [SIG-FOLD] cascade-signature identity ---

    @Test
    void signatureIsDeterministicAndDropsTrailAndWindow() {
        // Same (sequence, domain, snapshotId) -> same id REGARDLESS of trailId / sourceWindowId.
        // (trailId/sourceWindowId are simply not arguments — the fold key is the signature.)
        UUID a = UuidV5.signatureIdentity(List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded"),
                "core-ip", "snap-1");
        UUID b = UuidV5.signatureIdentity(List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded"),
                "core-ip", "snap-1");
        assertThat(a).isEqualTo(b);
        assertThat(a.version()).isEqualTo(5);
        assertThat(a.variant()).isEqualTo(2);
    }

    // AC-SF-4 (unit half): sequence order is significant.
    @Test
    void signatureIsOrderSignificant() {
        assertThat(UuidV5.signatureIdentity(List.of("A", "B", "C"), "core-ip", "snap-1"))
                .isNotEqualTo(UuidV5.signatureIdentity(List.of("B", "A", "C"), "core-ip", "snap-1"));
    }

    // AC-SF-5 (unit half): sequence repeats are significant (no consecutive-repeat collapse).
    @Test
    void signatureRepeatsSignificant() {
        assertThat(UuidV5.signatureIdentity(List.of("A", "B", "A"), "core-ip", "snap-1"))
                .isNotEqualTo(UuidV5.signatureIdentity(List.of("A", "B"), "core-ip", "snap-1"));
    }

    // AC-SF-10 (unit half): different snapshotId -> different signature id (snapshot-scoped).
    @Test
    void signatureSnapshotScoped() {
        UUID s1 = UuidV5.signatureIdentity(List.of("A", "B"), "core-ip", "snap-1");
        UUID s2 = UuidV5.signatureIdentity(List.of("A", "B"), "core-ip", "snap-2");
        assertThat(s1).isNotEqualTo(s2);
    }

    // AC-SF-3 (unit half): different sequences -> different id.
    @Test
    void signatureDifferentSequencesDiffer() {
        assertThat(UuidV5.signatureIdentity(List.of("IPLinkDown", "LinkDown"), "core-ip", "snap-1"))
                .isNotEqualTo(UuidV5.signatureIdentity(
                        List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded"), "core-ip", "snap-1"));
    }

    // Identity-space separation: signature id collides with neither the anchor nor the per-event id.
    @Test
    void signatureAndAnchorAndPerEventDoNotCollide() {
        UUID signature = UuidV5.signatureIdentity(List.of("LOS", "LinkDown"), "core-ip", "snap-1");
        UUID anchor = UuidV5.anchorIdentity("core-ip", "snap-1", "cb-1", "SC-FIBER");
        UUID perEvent = UuidV5.perEventIdentity("trail-1", List.of("LOS", "LinkDown"), "w1", "snap-1");
        assertThat(signature).isNotEqualTo(anchor);
        assertThat(signature).isNotEqualTo(perEvent);
    }
}
