package com.acp.correlationengine.correlate;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression: an alarm CORRELATED into a fired incident must NOT be reverted-open when a SIBLING
 * correlation instance (the same alarm on a different trail/pattern) later expires without a full
 * match.
 *
 * <p>Exposed by pattern generalization: an approved pattern matches any compatible trail, so one
 * alarm fans out to MULTIPLE {@code (trailId, patternId)} instances. Live evidence showed every
 * stuck-open incident-member alarm carrying the audit trail {@code correlated} THEN
 * {@code reverted from correlation: instance expired without a match} — the sibling instance's
 * expiry clobbered a legitimate correlation. The fix guards the revert with the set of alarmIds
 * already correlated in a fired incident.
 */
class RevertGuardSiblingExpiryTest {

    private static final long WINDOW = 60_000;

    /**
     * THE regression test. {@code shared} (LOS) opens both instances via multi-trail fan-out:
     * <ul>
     *   <li>Instance A on trail T1 / pattern Pa = [LOS, LinkDown] fully matches when {@code childA}
     *       arrives and FIRES an incident — {@code shared} (root cause) + {@code childA} become
     *       CORRELATED, and A is destroyed.</li>
     *   <li>Instance B on trail T2 / pattern Pb = [LOS, PortDown, CardFail] never completes: it
     *       holds {@code shared} plus a genuinely-uncorrelated relevant alarm {@code loneB}
     *       (PortDown, 2 of 3 with tolerance 0), then EXPIRES.</li>
     * </ul>
     * Assert: on B's expiry, CE fires reverted-open ONLY for {@code loneB} — NEVER for the
     * already-correlated {@code shared}.
     */
    @Test
    void siblingInstanceExpiry_doesNotRevertAlreadyCorrelatedAlarm() {
        EngineHarness h = new EngineHarness();
        // Both patterns are opened by LOS, on two different trails.
        h.addPattern(gapPattern("Pa", "T1", List.of("LOS", "LinkDown"), "LOS", WINDOW));
        // Pb is a 3-element pattern so a single extra alarm keeps instance B partial (never fires).
        h.addPattern(gapPattern("Pb", "T2", List.of("LOS", "PortDown", "CardFail"), "LOS", WINDOW));

        // shared LOS fans out to both trails: opens instance A (T1/Pa) and instance B (T2/Pb).
        h.feed(alarm("shared", "LOS"), List.of("T1", "T2"), T0);
        assertThat(h.engine.hasInstance("T1", "Pa")).isTrue();
        assertThat(h.engine.hasInstance("T2", "Pb")).isTrue();

        // A completes -> fires the incident -> shared + childA become CORRELATED, A destroyed.
        h.feed(alarm("childA", "LinkDown"), "T1", T0 + 5);
        assertThat(h.incidents.totalIncidents()).isEqualTo(1);
        assertThat(h.statuses.alarmIdsWith("correlated")).containsExactlyInAnyOrder("shared", "childA");
        assertThat(h.engine.hasInstance("T1", "Pa")).isFalse();

        // A genuinely-uncorrelated relevant alarm joins still-live instance B (2 of 3 — no match).
        h.feed(alarm("loneB", "PortDown"), "T2", T0 + 10);
        assertThat(h.engine.hasInstance("T2", "Pb")).isTrue();
        assertThat(h.incidents.totalIncidents()).isEqualTo(1); // Pb has NOT fired
        assertThat(h.statuses.alarmIdsWith("correlated")).doesNotContain("loneB");

        long revertsBefore = h.statuses.countWith("reverted-open");

        // Advance the clock past B's (gap-based) window from its last admission -> B expires
        // without a full match. loneB was admitted at T0+10, so the deadline is T0+10+WINDOW.
        h.tick(T0 + 10 + WINDOW + 1);
        assertThat(h.engine.hasInstance("T2", "Pb")).isFalse();

        // The guard: shared is NOT reverted (still correlated in the fired incident); loneB IS.
        assertThat(h.statuses.alarmIdsWith("reverted-open")).contains("loneB");
        assertThat(h.statuses.alarmIdsWith("reverted-open")).doesNotContain("shared");
        // childA was never in the expiring instance and is untouched.
        assertThat(h.statuses.alarmIdsWith("reverted-open")).doesNotContain("childA");

        // Metric: exactly ONE reverted-open fired for this expiry (loneB only) — the already-
        // correlated alarm is excluded from the reverted-open count.
        assertThat(h.statuses.countWith("reverted-open") - revertsBefore).isEqualTo(1);
    }

    /**
     * Guard scope: an alarm that was NEVER correlated must still be reverted on expiry (the existing
     * single-instance behaviour must be preserved — the guard only excludes correlated alarms).
     */
    @Test
    void uncorrelatedInstanceExpiry_stillRevertsAllAlarms() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", WINDOW));

        // Opens an instance that never completes; nothing is ever correlated.
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.tick(T0 + WINDOW + 1);

        assertThat(h.incidents.totalIncidents()).isZero();
        assertThat(h.statuses.alarmIdsWith("reverted-open")).containsExactly("a1");
        assertThat(h.statuses.countWith("reverted-open")).isEqualTo(1);
    }
}
