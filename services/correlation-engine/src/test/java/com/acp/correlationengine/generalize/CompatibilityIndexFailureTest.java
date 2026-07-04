package com.acp.correlationengine.generalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.pattern.PatternStore;
import com.acp.correlationengine.support.FakeTrailBuilderClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AC41 — a Trail Builder fetch failure must not corrupt the compatibility index. Drives
 * {@link CompatibilityIndexService#rebuildAll} with a {@link FakeTrailBuilderClient} set to fail a
 * specific trail's member fetch (and, separately, to fail the whole enumerate): the affected trail is
 * ABSENT from the index (never present with wrong/empty data), the index for every OTHER trail is
 * intact, and no exception aborts or corrupts the build.
 */
class CompatibilityIndexFailureTest {

    private static final String SNAP = "SNAP-1";

    private static PatternRef abc(String patternId, String discoveryTrailId) {
        return new PatternRef(patternId, discoveryTrailId,
                List.of("aType", "bType", "cType"), "aType", 0.9, 60_000, WindowType.GAP_BASED,
                Map.of("aType", "A", "bType", "B", "cType", "C"));
    }

    private static CompatibilityIndexService service(FakeTrailBuilderClient tb, PatternStore store) {
        return new CompatibilityIndexService(
                store, tb,
                new RequiredObjectTypesResolver(tb),
                new CompatibilityEvaluator(),
                CorrelationMetrics.NOOP,
                "core-ip");
    }

    /** A single trail whose member fetch 5xxes is omitted; all other trails index correctly. */
    @Test
    void ac41_failedTrailFetchIsAbsent_otherTrailsIntact() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        PatternStore store = new PatternStore();
        PatternRef p = abc("P", "T_disc");
        store.upsert(p);
        // Discovery + two more compatible trails; one of them will fail its member fetch.
        tb.declareTrail("T_disc", List.of("A", "B", "C"));
        tb.declareTrail("T_ok", List.of("A", "B", "C"));
        tb.declareTrail("T_fail", List.of("A", "B", "C"));
        tb.failTrail("T_fail"); // 5xx on member fetch — must be omitted (AC41)

        CompatibilityIndexService svc = service(tb, store);
        svc.rebuildAll(SNAP, "core-ip");

        // The failing trail is absent — not present-with-empty, not falsely compatible.
        assertThat(svc.current().compatibleTrailIds("P"))
                .containsExactlyInAnyOrder("T_disc", "T_ok")
                .doesNotContain("T_fail");
        assertThat(svc.patternsCompatibleWith("T_fail")).isEmpty();
        // Other trails still index correctly — no corruption from the one failure.
        assertThat(svc.patternsCompatibleWith("T_disc")).extracting(PatternRef::patternId)
                .containsExactly("P");
        assertThat(svc.patternsCompatibleWith("T_ok")).extracting(PatternRef::patternId)
                .containsExactly("P");
        assertThat(svc.isBuiltAtLeastOnce()).isTrue(); // build completed, not aborted
    }

    /** A total enumerate failure retains the last-good index rather than swapping in an empty one. */
    @Test
    void ac41_enumerateFailureRetainsLastGoodIndex() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        PatternStore store = new PatternStore();
        store.upsert(abc("P", "T_disc"));
        tb.declareTrail("T_disc", List.of("A", "B", "C"));
        tb.declareTrail("T_ok", List.of("A", "B", "C"));

        CompatibilityIndexService svc = service(tb, store);
        svc.rebuildAll(SNAP, "core-ip"); // good build
        assertThat(svc.current().compatibleTrailIds("P"))
                .containsExactlyInAnyOrder("T_disc", "T_ok");

        // Now the whole enumerate call fails — the rebuild must NOT swap in an empty index.
        tb.failEnumeration(true);
        svc.rebuildAll(SNAP, "core-ip");

        assertThat(svc.current().compatibleTrailIds("P"))
                .as("last-good index retained on enumerate failure")
                .containsExactlyInAnyOrder("T_disc", "T_ok");
    }

    /** A trail whose fetch fails on a fresh rebuild is simply dropped; the pattern keeps its other trails. */
    @Test
    void ac41_failingTrailDroppedOnRebuild_noStaleEntry() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        PatternStore store = new PatternStore();
        store.upsert(abc("P", "T_disc"));
        tb.declareTrail("T_disc", List.of("A", "B", "C"));
        tb.declareTrail("T2", List.of("A", "B", "C"));

        CompatibilityIndexService svc = service(tb, store);
        svc.rebuildAll(SNAP, "core-ip");
        assertThat(svc.current().compatibleTrailIds("P")).contains("T2");

        // A new snapshot in which T2's member fetch now fails: T2 must drop out cleanly.
        tb.failTrail("T2");
        svc.rebuildAll(SNAP, "core-ip");
        assertThat(svc.current().compatibleTrailIds("P"))
                .containsExactly("T_disc")
                .doesNotContain("T2");
    }
}
