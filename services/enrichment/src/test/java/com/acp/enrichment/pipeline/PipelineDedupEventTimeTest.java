package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.enrichment.support.TestRulesets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FIX #3 — full-pipeline proof that dedup windows on the alarm's logical {@code raisedAt}, NOT
 * wall-clock arrival.
 *
 * <p>In P2 HISTORY mode the Simulator batch-replays the whole corpus in &lt;1s wall-clock while the
 * alarms' {@code raisedAt} span hours. The harness wall-clock is held CONSTANT here (never advanced
 * between {@code process} calls) to reproduce exactly that batch replay: with the old wall-clock
 * dedup, two alarms hours apart in {@code raisedAt} would collapse as "duplicates"; with the fix
 * they must NOT. Genuine duplicates within the dedup window (measured by {@code raisedAt}) still
 * collapse.
 *
 * <p>These use {@code cleared}-state alarms so the assertion isolates the DEDUP decision at the
 * pipeline level: the self-clear stage holds only {@code raised} alarms, so a stream of clears flows
 * straight through self-clear/flap and reaches the emit stage, making the survivor count a direct
 * read-out of dedup. (The raised-side event-time behaviour is covered by {@link DedupStepTest}.)
 */
class PipelineDedupEventTimeTest {

    private Map<String, Object> simClear(String alarmId, String moId, Instant raisedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("alarmId", alarmId);
        m.put("managedObjectId", moId);
        m.put("alarmType", "InterfaceDown");
        m.put("eventType", "communicationsAlarm");
        m.put("probableCause", "interfaceDown");
        m.put("perceivedSeverity", "major");
        m.put("state", "cleared");
        m.put("raisedAt", raisedAt.toString());
        return m;
    }

    @Test
    void batchReplayHoursApartByRaisedAtIsNotCollapsed() {
        // simulator dedupWindow = 30s. Wall-clock held constant (batch replay); the two alarms
        // differ only in raisedAt, 5 hours apart -> distinct occurrences, both survive.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));
        Instant t0 = Instant.parse("2026-06-14T00:00:00Z");

        h.process(simClear("a-1", "Node:N5", t0), "simulator", Path.HISTORY);
        h.process(simClear("a-2", "Node:N5", t0.plusSeconds(5 * 3600)), "simulator", Path.HISTORY);

        assertThat(h.emitted)
                .as("two identical alarms 5h apart (raisedAt) must NOT be deduped")
                .hasSize(2);
        assertThat(h.emitted).allSatisfy(e ->
                assertThat(e.alarm().getManagedObjectId()).isEqualTo("Node:N5"));
    }

    @Test
    void genuineDuplicatesWithinRaisedAtWindowAreCollapsed() {
        // Two identical alarms whose raisedAt are 10s apart (within the 30s window) DO collapse to
        // one, even though wall-clock is held constant.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));
        Instant t0 = Instant.parse("2026-06-14T00:00:00Z");

        h.process(simClear("d-1", "Node:N5", t0), "simulator", Path.HISTORY);
        h.process(simClear("d-2", "Node:N5", t0.plusSeconds(10)), "simulator", Path.HISTORY);

        assertThat(h.emitted)
                .as("two identical alarms within the 30s raisedAt window collapse to one")
                .hasSize(1);
    }

    @Test
    void mixedCorpusRetainsDistinctOccurrencesAndCollapsesOnlyTrueDuplicates() {
        // A small batch-replay corpus with the wall-clock held constant: three distinct occurrences
        // (hours apart) plus one true duplicate (within-window) => 3 survive, not 1.
        PipelineHarness h = new PipelineHarness(List.of(TestRulesets.simulator(),
                TestRulesets.defaultRuleset()));
        Instant t0 = Instant.parse("2026-06-14T00:00:00Z");

        h.process(simClear("o-1", "Node:N5", t0), "simulator", Path.HISTORY);
        // True duplicate of o-1 (5s later, within 30s window) -> collapsed.
        h.process(simClear("o-1b", "Node:N5", t0.plusSeconds(5)), "simulator", Path.HISTORY);
        // Distinct occurrence 2h later -> survives.
        h.process(simClear("o-2", "Node:N5", t0.plusSeconds(2 * 3600)), "simulator", Path.HISTORY);
        // Distinct occurrence 6h later -> survives.
        h.process(simClear("o-3", "Node:N5", t0.plusSeconds(6 * 3600)), "simulator", Path.HISTORY);

        assertThat(h.emitted)
                .as("3 distinct raisedAt occurrences survive; the 1 within-window duplicate collapses")
                .hasSize(3);
    }
}
