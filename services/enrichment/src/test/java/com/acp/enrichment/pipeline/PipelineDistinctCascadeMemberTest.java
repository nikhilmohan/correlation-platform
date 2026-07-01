package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.support.TestRulesets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Defect #7 — full-pipeline proof that the windowed-state key includes {@code alarmType}, so
 * dedup/flap-damp/self-clear collapse only genuinely-identical alarms (same object, same
 * {@code eventType}, SAME {@code alarmType}) and NOT distinct cascade members that merely share a
 * coarse X.733 {@code eventType}.
 *
 * <p>Domain fact this reproduces: a fault cascade fires MANY DISTINCT {@code alarmType}s on ONE
 * object, and many of them share ONE coarse {@code eventType} (e.g. six IGP-adjacency alarms —
 * {@code AdjDown}, {@code ISISAdjacencyDown}, {@code OSPFAdjacencyDown}, {@code BGPPeerDown},
 * {@code RouteFlap}, {@code LDPSessionDown} — all {@code communicationsAlarm}). Under the too-coarse
 * key {@code (path, source, managedObjectId, eventType[, state])} the pipeline treated these as
 * repeats/oscillations of one alarm and collapsed them onto one arbitrary survivor, silently eating
 * the very sequence steps the pattern-miner needs. With {@code alarmType} in the key each cascade
 * member is its own window, so all survive.
 *
 * <p>The simulator ruleset is the ideal driver: it is an identity passthrough over {@code alarmType}
 * (each canonical token maps to itself) and its {@code eventType} is identity, so a raw alarm with
 * {@code eventType=communicationsAlarm} and a distinct {@code alarmType} normalises to exactly those
 * canonical values on one {@code managedObjectId}. dedupWindow=30s, hold=15s, flapN=5, flapWindow=60s.
 */
class PipelineDistinctCascadeMemberTest {

    private static final Instant T0 = Instant.parse("2026-06-14T00:00:00Z");

    /** The six IGP-adjacency cascade members that all share the coarse {@code communicationsAlarm}. */
    private static final List<String> IGP_CASCADE = List.of(
            "AdjDown", "ISISAdjacencyDown", "OSPFAdjacencyDown",
            "BGPPeerDown", "RouteFlap", "LDPSessionDown");

    private Map<String, Object> simAlarm(String alarmId, String moId, String alarmType,
            String state, Instant raisedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("managedObjectId", moId);
        m.put("alarmType", alarmType);
        // All cascade members deliberately share ONE coarse eventType — the whole point.
        m.put("eventType", "communicationsAlarm");
        m.put("probableCause", "interfaceDown");
        m.put("perceivedSeverity", "major");
        m.put("state", state);
        m.put("raisedAt", raisedAt.toString());
        return m;
    }

    // ----------------------------------------------------------------------------------------------
    // The defect proof: distinct cascade members must NOT collapse.
    // ----------------------------------------------------------------------------------------------

    @Test
    void dedup_distinctAlarmTypesSharingOneEventType_allSurvive() {
        // Six DISTINCT alarmTypes on ONE object, all communicationsAlarm, within the 30s dedup
        // window (raisedAt spread over 5s). state=cleared isolates the DEDUP decision (self-clear
        // holds only raised, so clears flow straight through to the emit stage — survivor count is a
        // direct read-out of dedup). Under the too-coarse key only 1 survives (5 eaten as
        // "duplicates"); with alarmType in the key all 6 survive.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));

        int i = 0;
        for (String type : IGP_CASCADE) {
            h.process(simAlarm("d-" + i, "IGPAdjacency:N13_N14", type, "cleared",
                    T0.plusSeconds(i)), "simulator", Path.HISTORY);
            i++;
        }

        assertThat(h.emitted)
                .as("six distinct cascade-member alarmTypes on one (moid, eventType) must all "
                        + "survive dedup — none collapsed onto an arbitrary survivor")
                .hasSize(IGP_CASCADE.size());
        assertThat(h.emitted.stream().map(e -> e.alarm().getAlarmType())
                .collect(Collectors.toSet()))
                .as("each distinct alarmType is retained exactly once")
                .containsExactlyInAnyOrderElementsOf(IGP_CASCADE);
    }

    @Test
    void flap_distinctAlarmTypesSharingOneEventType_notMiscountedAsOneFlap() {
        // Six DISTINCT alarmTypes on ONE object, all communicationsAlarm, all within the 60s flap
        // window (raisedAt spread over ~7s), state=cleared so they pass self-clear untouched and
        // reach FlapDamp directly. Under the too-coarse key FlapDamp counts them as 6 oscillations of
        // ONE alarm, exceeds flapN=5, emits ONE summary and drops the rest. With alarmType in the key
        // each is its own (single-occurrence) window — no flapping, no summary, all 6 survive intact.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));

        int i = 0;
        for (String type : IGP_CASCADE) {
            // Spread within the 60s flap window but note dedup is per-alarmType so no dedup collapse.
            h.process(simAlarm("f-" + i, "IGPAdjacency:N13_N14", type, "cleared",
                    T0.plusSeconds(i)), "simulator", Path.HISTORY);
            i++;
        }

        assertThat(h.emitted)
                .as("distinct cascade members must not be flap-collapsed onto one survivor")
                .hasSize(IGP_CASCADE.size());
        long summaries = h.emitted.stream()
                .filter(e -> e.alarm().getVendorRaw() != null
                        && e.alarm().getVendorRaw().getAdditionalProperties()
                                .containsKey("flapCount"))
                .count();
        assertThat(summaries)
                .as("distinct alarmTypes are not oscillations of one alarm — no flap summary")
                .isZero();
    }

    @Test
    void selfClear_clearOfOneMember_doesNotCancelHeldRaiseOfAnotherMember() {
        // Self-clear must match a raise with a clear of the SAME alarm. A held RAISE of AdjDown and a
        // later CLEAR of BGPPeerDown share (moid, eventType) but are DIFFERENT alarms — the clear must
        // NOT suppress the AdjDown raise. Under the too-coarse key the BGPPeerDown clear would cancel
        // the AdjDown held raise (both eaten). With alarmType in the key they are separate holds: the
        // BGPPeerDown clear finds no matching held raise (flows through), and the AdjDown raise is
        // released on drain.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));

        // Hold a raise of AdjDown.
        h.process(simAlarm("r-adj", "IGPAdjacency:N0_N1", "AdjDown", "raised", T0),
                "simulator", Path.HISTORY);
        // A clear of a DIFFERENT member arrives within the 15s hold — must not cancel the AdjDown hold.
        h.process(simAlarm("c-bgp", "IGPAdjacency:N0_N1", "BGPPeerDown", "cleared",
                T0.plusSeconds(3)), "simulator", Path.HISTORY);
        // Drain releases the still-held AdjDown raise.
        h.pipeline.drainSelfClearHolds();

        List<String> emittedTypes = h.emitted.stream().map(e -> e.alarm().getAlarmType())
                .collect(Collectors.toList());
        assertThat(emittedTypes)
                .as("the AdjDown raise must be released (not cancelled by a BGPPeerDown clear) and "
                        + "the unmatched BGPPeerDown clear must flow through")
                .containsExactlyInAnyOrder("AdjDown", "BGPPeerDown");
    }

    // ----------------------------------------------------------------------------------------------
    // Negative controls: legitimate collapse of genuinely-IDENTICAL alarms still works.
    // ----------------------------------------------------------------------------------------------

    @Test
    void dedup_identicalAlarmsSameAlarmType_stillCollapse() {
        // Genuine duplicates: SAME moid, eventType, alarmType, state, within the 30s window -> still
        // collapse to one. Proves alarmType in the key did not disable dedup.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));

        h.process(simAlarm("dup-1", "IGPAdjacency:N0_N1", "AdjDown", "cleared", T0),
                "simulator", Path.HISTORY);
        h.process(simAlarm("dup-2", "IGPAdjacency:N0_N1", "AdjDown", "cleared",
                T0.plusSeconds(10)), "simulator", Path.HISTORY);

        assertThat(h.emitted)
                .as("two genuinely-identical alarms within the window still collapse to one")
                .hasSize(1);
    }

    @Test
    void flap_genuineOscillationOfSameAlarmType_stillYieldsOneSummary() {
        // Genuine flap: raise/clear oscillation of the SAME alarmType exceeding flapN within the flap
        // window -> exactly one summary. Proves alarmType in the key did not disable flap-damp. Uses
        // the flap-source tuning (hold=1s, flapN=3, flapWindow=300s, dedupWindow=1s) with a fixed
        // alarmType, mirroring PipelineSelfClearAndFlapTest#criterion3.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.flapSourceSim(),
                TestRulesets.defaultRuleset()));

        for (int i = 1; i <= 6; i++) {
            Instant raiseAt = T0.plusSeconds(i * 10L);
            h.process(flapSim("raise-" + i, "LinkDown", "raised", raiseAt),
                    "flap-source-sim", Path.HISTORY);
            h.clock.advance(Duration.ofSeconds(2)); // past the 1s hold
            h.sweepSelfClear();                     // release held raise into FlapDamp
            h.process(flapSim("clear-" + i, "LinkDown", "cleared", raiseAt.plusSeconds(3)),
                    "flap-source-sim", Path.HISTORY);
            h.clock.advance(Duration.ofSeconds(2));
            h.sweepSelfClear();
        }

        long summaries = h.emitted.stream()
                .filter(e -> e.alarm().getVendorRaw() != null
                        && e.alarm().getVendorRaw().getAdditionalProperties()
                                .containsKey("flapCount"))
                .count();
        assertThat(summaries)
                .as("a genuine oscillation of the SAME alarmType still yields exactly one summary")
                .isEqualTo(1);
    }

    @Test
    void selfClear_raisePlusClearSameAlarmType_stillSuppressed() {
        // Criterion 4 unchanged: a raise + a clear of the SAME alarmType within the hold-time are a
        // transient and are suppressed entirely. Proves alarmType in the key did not disable
        // self-clear matching.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));

        h.process(simAlarm("r-1", "IGPAdjacency:N0_N1", "AdjDown", "raised", T0),
                "simulator", Path.HISTORY);
        // Clear of the SAME alarmType 3s later — within the 15s hold.
        h.process(simAlarm("c-1", "IGPAdjacency:N0_N1", "AdjDown", "cleared", T0.plusSeconds(3)),
                "simulator", Path.HISTORY);
        h.pipeline.drainSelfClearHolds();

        assertThat(h.emitted)
                .as("raise + clear of the SAME alarmType within the hold is a suppressed transient")
                .isEmpty();
    }

    // A raw alarm for the flap-source-sim ruleset (identity passthrough over alarmType + eventType).
    private Map<String, Object> flapSim(String alarmId, String alarmType, String state,
            Instant raisedAt) {
        return simAlarm(alarmId, "Interface:edge9-1", alarmType, state, raisedAt);
    }
}
