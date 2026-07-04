package com.acp.correlationengine.correlate;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static com.acp.correlationengine.support.Fixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.support.EngineHarness;
import com.acp.correlationengine.support.FixedKnowledgeParams;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Codebook decode + conflict resolution acceptance criteria (AC9, AC10, AC11, AC12, AC26). */
class CodebookAndConflictTest {

    /** AC9 — codebook cold-start: closest-match decode with no active pattern instance. */
    @Test
    void ac9_codebookColdStart_decodesWithoutPatternInstance() {
        EngineHarness h = new EngineHarness();
        // No pattern on trail T; only a codebook scenario.
        h.addSignatures("snap1", "T", List.of(
                scenario("CODEBOOK-1", "T", "S1", "LOS", List.of("LOS", "LinkDown"))));

        h.feed(alarm("root", "LOS"), "T", T0);
        h.feed(alarm("child", "LinkDown"), "T", T0 + 1);
        h.tick(T0 + 2); // uncovered-buffer decode runs on the tick

        assertThat(h.results.emitted).hasSize(1);
        Incident inc = h.results.emitted.get(0);
        assertThat(inc.matchedCodebookId()).isEqualTo("CODEBOOK-1"); // artifact id, not scenarioId
        assertThat(inc.matchedPatternId()).isNull();
        assertThat(inc.rootCauseAlarmId()).isEqualTo("root"); // resolved from rootCauseAlarmType LOS
    }

    /** AC10 — fiber-cut storm: one alarm dropped, partial-match tolerance permits N-1 of N. */
    @Test
    void ac10_fiberCutStorm_partialMatchTolerated() {
        // tolerance = 1 => N-1 of N counts as a full match
        MatchParams params = new MatchParams(1, 1.0, 2.0, 0.5, 1.0, 0.1);
        EngineHarness h = new EngineHarness(params);
        h.addPattern(gapPattern("P", "T",
                List.of("LOS", "LinkDown", "PortDown"), "LOS", 60_000));

        h.feed(alarm("los", "LOS"), "T", T0);
        h.feed(alarm("link", "LinkDown"), "T", T0 + 1);
        // PortDown dropped from the stream — only 2 of 3, but tolerance permits it

        assertThat(h.results.emitted).hasSize(1);
        Incident inc = h.results.emitted.get(0);
        assertThat(inc.rootCauseAlarmId()).isEqualTo("los");
        assertThat(inc.childAlarmIds()).containsExactly("link");
    }

    /** AC11 — deterministic conflict resolution: specificity first, then confidence. */
    @Test
    void ac11_conflictResolution_specificityThenConfidence() {
        ConflictResolver resolver = new ConflictResolver();
        MatchParams params = FixedKnowledgeParams.defaults();

        List<ObservedAlarm> three = List.of(
                alarm("a1", "LOS"), alarm("a2", "LinkDown"), alarm("a3", "PortDown"));
        List<ObservedAlarm> two = List.of(alarm("a1", "LOS"), alarm("a2", "LinkDown"));

        MatchCandidate broader = new MatchCandidate(
                MatchCandidate.MatchType.PATTERN, "T", "LOS", three, 0.5, "A", null);
        MatchCandidate narrowerHigherConf = new MatchCandidate(
                MatchCandidate.MatchType.PATTERN, "T", "LOS", two, 0.99, "B", null);

        // specificity wins over confidence — A (3 alarms) beats B (2 alarms) even with lower conf
        Optional<MatchCandidate> winner = resolver.resolve(List.of(narrowerHigherConf, broader), params);
        assertThat(winner).map(MatchCandidate::matchedPatternId).contains("A");

        // deterministic across repeated replays
        for (int i = 0; i < 20; i++) {
            assertThat(resolver.resolve(List.of(broader, narrowerHigherConf), params))
                    .map(MatchCandidate::matchedPatternId).contains("A");
        }

        // tie on specificity — higher confidence wins
        MatchCandidate tieLowConf = new MatchCandidate(
                MatchCandidate.MatchType.PATTERN, "T", "LOS", two, 0.4, "C", null);
        MatchCandidate tieHighConf = new MatchCandidate(
                MatchCandidate.MatchType.PATTERN, "T", "LOS", two, 0.8, "D", null);
        assertThat(resolver.resolve(List.of(tieLowConf, tieHighConf), params))
                .map(MatchCandidate::matchedPatternId).contains("D");
    }

    /** AC12 — codebook tolerance: one missing + one spurious alarm still selects the scenario. */
    @Test
    void ac12_codebookTolerance_missingAndExtraAlarms() {
        // low floor so a near-match clears it; penalties from Knowledge (no hard-coded values)
        MatchParams params = new MatchParams(0, 1.0, 1.0, 0.3, 1.0, 0.1);
        EngineHarness h = new EngineHarness(params);
        // scenario S = {LOS, LinkDown, PortDown}
        h.addSignatures("snap1", "T", List.of(
                scenario("CODEBOOK-1", "T", "S1", "LOS",
                        List.of("LOS", "LinkDown", "PortDown"))));

        // observed: missing PortDown, plus a spurious CardFault
        h.feed(alarm("root", "LOS"), "T", T0);
        h.feed(alarm("link", "LinkDown"), "T", T0 + 1);
        h.feed(alarm("noise", "CardFault"), "T", T0 + 2);
        h.tick(T0 + 3);

        assertThat(h.results.emitted).hasSize(1);
        assertThat(h.results.emitted.get(0).matchedCodebookId()).isEqualTo("CODEBOOK-1");
    }

    /** AC26 — rootCauseAlarmId resolved by alarmType, NOT eventType/probableCause. */
    @Test
    void ac26_rootCauseResolvedByAlarmType_notEventTypeOrProbableCause() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));

        // Both alarms would (in a broken impl) look identical on eventType/probableCause; only the
        // alarmType distinguishes them. ObservedAlarm carries alarmType as the join key. The alarm
        // with alarmType == "LOS" (the pattern's rootCauseAlarmType) must be picked as root cause.
        h.feed(alarm("theRoot", "LOS"), "T", T0);
        h.feed(alarm("theChild", "LinkDown"), "T", T0 + 1);

        Incident inc = h.results.emitted.get(0);
        assertThat(inc.rootCauseAlarmId()).isEqualTo("theRoot");
        assertThat(inc.rootCauseAlarmType()).isEqualTo("LOS");
        assertThat(inc.childAlarmIds()).containsExactly("theChild");
    }
}
