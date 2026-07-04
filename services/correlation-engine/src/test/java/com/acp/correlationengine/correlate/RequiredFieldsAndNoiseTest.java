package com.acp.correlationengine.correlate;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static com.acp.correlationengine.support.Fixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Required-fields population for pattern (AC14) + codebook (AC15) incidents, and the engine-scoped
 * noise-tolerant match within the session window (AC30 unit portion — the full noisy-stream rates
 * are asserted by the integration oracle).
 */
class RequiredFieldsAndNoiseTest {

    /** AC14 — pattern-match incident: matchedPatternId non-null, confidence in [0,1], trailId matches. */
    @Test
    void ac14_patternMatch_requiredFieldsPopulated() {
        EngineHarness h = new EngineHarness();
        h.addPattern(gapPattern("PAT-1", "TRAIL-9", List.of("LOS", "LinkDown"), "LOS", 60_000));

        h.feed(alarm("root", "LOS"), "TRAIL-9", T0);
        h.feed(alarm("child", "LinkDown"), "TRAIL-9", T0 + 1);

        Incident inc = h.results.emitted.get(0);
        assertThat(inc.matchedPatternId()).isEqualTo("PAT-1");
        assertThat(inc.matchedCodebookId()).isNull();
        assertThat(inc.confidence()).isBetween(0.0, 1.0);
        assertThat(inc.trailId()).isEqualTo("TRAIL-9");
    }

    /** AC15 — codebook-decode incident: matchedCodebookId = artifact id, matchedPatternId null. */
    @Test
    void ac15_codebookMatch_requiredFieldsPopulated() {
        EngineHarness h = new EngineHarness();
        h.addSignatures("snap1", "TRAIL-5", List.of(
                scenario("CODEBOOK-3", "TRAIL-5", "S1", "LOS", List.of("LOS", "LinkDown"))));

        h.feed(alarm("root", "LOS"), "TRAIL-5", T0);
        h.feed(alarm("child", "LinkDown"), "TRAIL-5", T0 + 1);
        h.tick(T0 + 2);

        Incident inc = h.results.emitted.get(0);
        assertThat(inc.matchedCodebookId()).isEqualTo("CODEBOOK-3");
        assertThat(inc.matchedPatternId()).isNull();
        assertThat(inc.confidence()).isBetween(0.0, 1.0);
        assertThat(inc.trailId()).isEqualTo("TRAIL-5");
    }

    /** AC30 (engine-scoped) — a seeded cascade interleaved with noise still fires within the window. */
    @Test
    void ac30_noiseTolerantMatchFiresWithinWindow() {
        // tolerance 1 => a dropped cascade symptom does not block the match
        MatchParams params = new MatchParams(1, 1.0, 2.0, 0.5, 1.0, 0.1);
        EngineHarness h = new EngineHarness(params);
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown", "PortDown"), "LOS", 30_000));

        // cascade + interleaved background/noise on the same trail
        h.feed(alarm("los", "LOS"), "T", T0);
        h.feed(alarm("noise1", "CardFault"), "T", T0 + 5); // unrelated — not admitted
        h.feed(alarm("link", "LinkDown"), "T", T0 + 10);
        h.feed(alarm("noise2", "PsuWarning"), "T", T0 + 15); // unrelated — not admitted
        // PortDown dropped (noisy stream) — 2 of 3, tolerance permits

        assertThat(h.results.emitted).hasSize(1);
        Incident inc = h.results.emitted.get(0);
        assertThat(inc.rootCauseAlarmId()).isEqualTo("los");
        assertThat(inc.childAlarmIds()).containsExactly("link"); // noise excluded
    }
}
