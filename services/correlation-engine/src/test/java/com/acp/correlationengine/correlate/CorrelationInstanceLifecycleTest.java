package com.acp.correlationengine.correlate;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Correlation-instance lifecycle acceptance criteria (AC1-AC8, AC16) driven through the Kafka-free
 * {@link CorrelationEngine} core.
 */
class CorrelationInstanceLifecycleTest {

    /** AC1 — first matching alarm creates exactly one instance; none exists before it. */
    @Test
    void ac1_lazyInit_firstMatchingAlarmCreatesExactlyOneInstance() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P1", "T1", List.of("LOS", "LinkDown"), "LOS", 60_000));

        assertThat(h.engine.hasInstance("T1", "P1")).isFalse();

        h.feed(alarm("a1", "LOS"), "T1", T0);

        assertThat(h.engine.hasInstance("T1", "P1")).isTrue();
        assertThat(h.engine.activeInstanceCount()).isEqualTo(1);
    }

    /** AC2 — one alarm on two trails initiates two independent, isolated instances. */
    @Test
    void ac2_multiTrailFanOut_twoIndependentInstances() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("Pa", "T1", List.of("LOS", "LinkDown"), "LOS", 60_000));
        h.addPattern(gapPattern("Pb", "T2", List.of("LOS", "PortDown"), "LOS", 60_000));

        h.feed(alarm("a1", "LOS"), List.of("T1", "T2"), T0);

        assertThat(h.engine.hasInstance("T1", "Pa")).isTrue();
        assertThat(h.engine.hasInstance("T2", "Pb")).isTrue();
        assertThat(h.engine.activeInstanceCount()).isEqualTo(2);
    }

    /** AC3 — a second relevant alarm is added to the existing instance, not a new one. */
    @Test
    void ac3_addToExisting_secondAlarmJoinsSameInstance() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown", "PortDown"), "LOS", 60_000));

        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 10);

        // still in progress (3-element sequence, 2 satisfied), exactly one instance
        assertThat(h.engine.hasInstance("T", "P")).isTrue();
        assertThat(h.engine.activeInstanceCount()).isEqualTo(1);
        assertThat(h.results.emitted).isEmpty();
    }

    /** AC4 — full match fires immediately: one incident, one result, correlated for all, destroy. */
    @Test
    void ac4_fullMatch_firesAndDestroysImmediately() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        h.feed(alarm("root", "LOS"), "T", T0);
        h.feed(alarm("child", "LinkDown"), "T", T0 + 5);

        assertThat(h.results.emitted).hasSize(1);
        assertThat(h.incidents.totalIncidents()).isEqualTo(1);
        // correlated fired for root + child
        assertThat(h.statuses.alarmIdsWith("correlated")).containsExactlyInAnyOrder("root", "child");
        // instance destroyed — no live instance remains
        assertThat(h.engine.hasInstance("T", "P")).isFalse();
        assertThat(h.engine.activeInstanceCount()).isZero();
    }

    /** AC5 — session expiry destroys the instance, creates no incident, reverts every alarm. */
    @Test
    void ac5_sessionExpiry_destroysAndReverts() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        h.feed(alarm("a1", "LOS"), "T", T0); // opens instance, no full match yet
        h.tick(T0 + 60_001); // past the window

        assertThat(h.engine.hasInstance("T", "P")).isFalse();
        assertThat(h.results.emitted).isEmpty();
        assertThat(h.incidents.totalIncidents()).isZero();
        assertThat(h.statuses.alarmIdsWith("reverted-open")).containsExactly("a1");
    }

    /** AC6 — exactly one in-progress AlarmStatusChange on admission, matching alarmId. */
    @Test
    void ac6_inProgressStatusOnAdmission() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        h.feed(alarm("a1", "LOS"), "T", T0);

        assertThat(h.statuses.alarmIdsWith("in-progress")).containsExactly("a1");
        assertThat(h.statuses.countWith("in-progress")).isEqualTo(1);
    }

    /** AC7 — per-pattern session windows are independent (W1 != W2). */
    @Test
    void ac7_perPatternWindowsAreIndependent() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P1", "T", List.of("LOS", "LinkDown"), "LOS", 10_000)); // W1
        h.addPattern(gapPattern("P2", "T", List.of("LOS", "PortDown"), "LOS", 60_000)); // W2

        h.feed(alarm("a1", "LOS"), "T", T0); // opens both instances

        h.tick(T0 + 20_000); // past W1, before W2

        assertThat(h.engine.hasInstance("T", "P1")).isFalse(); // expired at W1
        assertThat(h.engine.hasInstance("T", "P2")).isTrue();  // still alive under W2
    }

    /** AC8 — concurrent instances produce independent incidents with disjoint children. */
    @Test
    void ac8_isolation_concurrentInstancesDisjointIncidents() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P1", "T1", List.of("LOS", "LinkDown"), "LOS", 60_000));
        h.addPattern(gapPattern("P2", "T2", List.of("LOS", "PortDown"), "LOS", 60_000));

        // two sequences arriving "simultaneously" from different topology parts
        h.feed(alarm("r1", "LOS"), "T1", T0);
        h.feed(alarm("r2", "LOS"), "T2", T0);
        h.feed(alarm("c1", "LinkDown"), "T1", T0 + 1);
        h.feed(alarm("c2", "PortDown"), "T2", T0 + 1);

        assertThat(h.results.emitted).hasSize(2);
        Incident i1 = h.results.emitted.stream()
                .filter(i -> i.trailId().equals("T1")).findFirst().orElseThrow();
        Incident i2 = h.results.emitted.stream()
                .filter(i -> i.trailId().equals("T2")).findFirst().orElseThrow();
        assertThat(i1.childAlarmIds()).containsExactly("c1");
        assertThat(i2.childAlarmIds()).containsExactly("c2");
        // no alarm appears in both incidents
        assertThat(i1.childAlarmIds()).doesNotContainAnyElementsOf(i2.childAlarmIds());
        assertThat(i1.rootCauseAlarmId()).isNotEqualTo(i2.rootCauseAlarmId());
    }

    /** AC16 — a duplicate alarmId does not create a duplicate instance or incident. */
    @Test
    void ac16_idempotency_duplicateAlarmProcessedOnce() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a1", "LOS"), "T", T0 + 1); // redelivered same alarmId — no-op

        assertThat(h.engine.activeInstanceCount()).isEqualTo(1);
        assertThat(h.engine.totalAlarmsProcessed()).isEqualTo(1);
        assertThat(h.statuses.countWith("in-progress")).isEqualTo(1);

        h.feed(alarm("child", "LinkDown"), "T", T0 + 2);
        assertThat(h.incidents.totalIncidents()).isEqualTo(1);
    }
}
