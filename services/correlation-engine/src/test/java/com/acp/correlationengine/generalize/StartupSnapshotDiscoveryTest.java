package com.acp.correlationengine.generalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
import com.acp.correlationengine.pattern.PatternManagerClient;
import com.acp.correlationengine.support.EngineHarness;
import com.acp.correlationengine.topology.TopologyClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Startup-bootstrap fix (live-found STARTUP gap): on restart the {@code trails.built} events were
 * already consumed + committed, so the compatibility index stayed EMPTY forever (0 auto-correlation)
 * because {@code currentSnapshotId} was never re-learned. {@link StartupSnapshotDiscovery} closes the
 * gap by DISCOVERING the current snapshot at boot (Topology {@code GET /topology/snapshots}, with an
 * approved-pattern {@code supportingInstances[].snapshotId} fallback) and building the index against
 * it — never depending on a live {@code trails.built} event arriving.
 */
class StartupSnapshotDiscoveryTest {

    private static PatternRef abcPattern(String patternId, String discoveryTrailId) {
        return new PatternRef(patternId, discoveryTrailId,
                List.of("aType", "bType", "cType"), "aType", 0.9, 60_000, WindowType.GAP_BASED,
                Map.of("aType", "A", "bType", "B", "cType", "C"));
    }

    /** Wire a StartupSnapshotDiscovery over the harness's real index service + a declared, un-rebuilt catalog. */
    private static StartupSnapshotDiscovery discovery(EngineHarness h, TopologyClient topology,
            PatternManagerClient pm) {
        return new StartupSnapshotDiscovery(topology, pm, h.compatibilityIndex, "core-ip");
    }

    /**
     * Topology returns a current snapshot at startup -> discovery calls rebuildAll with THAT snapshot
     * and the index is populated (approved pattern -> compatible trails non-empty), even though no
     * trails.built event ever arrived and no rebuild had run yet.
     */
    @Test
    void topologyReturnsCurrentSnapshot_indexIsPopulatedAtStartup() {
        EngineHarness h = new EngineHarness();
        // Approved pattern + a compatible trail exist in the catalog, but the index has NOT been built
        // (no trails.built consumed on this restart) — this is the empty-index startup state.
        h.patternStore.upsert(abcPattern("P", "T_disc"));
        h.declareTrail("T_other", List.of("A", "B", "C"));
        assertThat(h.compatibilityIndex.isBuiltAtLeastOnce()).isFalse();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other")).isEmpty();

        TopologyClient topology = mock(TopologyClient.class);
        when(topology.currentSnapshotId("core-ip")).thenReturn(Optional.of("SNAP-CURRENT"));
        PatternManagerClient pm = mock(PatternManagerClient.class); // fallback not needed

        discovery(h, topology, pm).discoverAndBuild();

        assertThat(h.compatibilityIndex.isBuiltAtLeastOnce()).isTrue();
        assertThat(h.compatibilityIndex.currentSnapshotId()).isEqualTo("SNAP-CURRENT");
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other"))
                .extracting(PatternRef::patternId).containsExactly("P");
    }

    /**
     * Topology is unreachable at startup (empty) -> discovery FALLS BACK to the approved patterns'
     * supportingInstances[].snapshotId and still builds the index.
     */
    @Test
    void topologyUnreachable_fallsBackToApprovedPatternSnapshot_indexStillBuilds() {
        EngineHarness h = new EngineHarness();
        h.patternStore.upsert(abcPattern("P", "T_disc"));
        h.declareTrail("T_other", List.of("A", "B", "C"));

        TopologyClient topology = mock(TopologyClient.class);
        when(topology.currentSnapshotId("core-ip")).thenReturn(Optional.empty()); // unreachable/none
        PatternManagerClient pm = mock(PatternManagerClient.class);
        when(pm.discoverSnapshotId()).thenReturn(Optional.of("SNAP-FROM-PATTERN"));

        discovery(h, topology, pm).discoverAndBuild();

        assertThat(h.compatibilityIndex.isBuiltAtLeastOnce()).isTrue();
        assertThat(h.compatibilityIndex.currentSnapshotId()).isEqualTo("SNAP-FROM-PATTERN");
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other"))
                .extracting(PatternRef::patternId).containsExactly("P");
    }

    /**
     * Both sources unavailable -> index stays empty (no crash), and a LATER trails.built-triggered
     * rebuild still populates it (the pre-fix event path is preserved).
     */
    @Test
    void bothSourcesUnavailable_indexEmpty_laterTrailsBuiltStillRebuilds() {
        EngineHarness h = new EngineHarness();
        h.patternStore.upsert(abcPattern("P", "T_disc"));
        h.declareTrail("T_other", List.of("A", "B", "C"));

        TopologyClient topology = mock(TopologyClient.class);
        when(topology.currentSnapshotId("core-ip")).thenReturn(Optional.empty());
        PatternManagerClient pm = mock(PatternManagerClient.class);
        when(pm.discoverSnapshotId()).thenReturn(Optional.empty());

        discovery(h, topology, pm).discoverAndBuild(); // no crash

        assertThat(h.compatibilityIndex.isBuiltAtLeastOnce()).isFalse();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other")).isEmpty();

        // A later trails.built event arrives -> the existing event path rebuilds the index.
        h.compatibilityIndex.rebuildAll("SNAP-LATE", "core-ip");

        assertThat(h.compatibilityIndex.isBuiltAtLeastOnce()).isTrue();
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other"))
                .extracting(PatternRef::patternId).containsExactly("P");
    }

    /** Topology discovery THROWS -> swallowed, falls back to the approved-pattern snapshot. */
    @Test
    void topologyThrows_fallsBackToApprovedPatternSnapshot() {
        EngineHarness h = new EngineHarness();
        h.patternStore.upsert(abcPattern("P", "T_disc"));
        h.declareTrail("T_other", List.of("A", "B", "C"));

        TopologyClient topology = mock(TopologyClient.class);
        when(topology.currentSnapshotId("core-ip")).thenThrow(new RuntimeException("topology down"));
        PatternManagerClient pm = mock(PatternManagerClient.class);
        when(pm.discoverSnapshotId()).thenReturn(Optional.of("SNAP-FALLBACK"));

        discovery(h, topology, pm).discoverAndBuild();

        assertThat(h.compatibilityIndex.currentSnapshotId()).isEqualTo("SNAP-FALLBACK");
        assertThat(h.compatibilityIndex.patternsCompatibleWith("T_other")).hasSize(1);
    }
}
