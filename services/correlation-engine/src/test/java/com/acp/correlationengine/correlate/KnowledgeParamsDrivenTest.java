package com.acp.correlationengine.correlate;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;
import static com.acp.correlationengine.support.Fixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.support.EngineHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC21 — every match-quality/conflict threshold comes from the Knowledge Service. Replacing the
 * params with values different from any default changes the outcomes with no code change; no
 * threshold is hard-coded in the engine.
 */
class KnowledgeParamsDrivenTest {

    /** partialMatchTolerance from Knowledge governs whether N-1 of N fires. */
    @Test
    void ac21_partialMatchTolerance_isKnowledgeSourced() {
        // tolerance 0 => 2 of 3 does NOT fire
        EngineHarness strict = new EngineHarness(new MatchParams(0, 1, 2, 0.5, 1, 0.1));
        strict.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown", "PortDown"), "LOS", 60_000));
        strict.feed(alarm("los", "LOS"), "T", T0);
        strict.feed(alarm("link", "LinkDown"), "T", T0 + 1);
        assertThat(strict.results.emitted).isEmpty();

        // tolerance 1 => the SAME input DOES fire — only the Knowledge param changed
        EngineHarness lenient = new EngineHarness(new MatchParams(1, 1, 2, 0.5, 1, 0.1));
        lenient.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown", "PortDown"), "LOS", 60_000));
        lenient.feed(alarm("los", "LOS"), "T", T0);
        lenient.feed(alarm("link", "LinkDown"), "T", T0 + 1);
        assertThat(lenient.results.emitted).hasSize(1);
    }

    /** codebook.scoreFloor from Knowledge governs whether a near-match is admitted. */
    @Test
    void ac21_codebookScoreFloor_isKnowledgeSourced() {
        List<com.acp.correlationengine.model.TrailScenarioSignature> sigs = List.of(
                scenario("CODEBOOK-1", "T", "S1", "LOS", List.of("LOS", "LinkDown", "PortDown")));

        // high floor 0.99 => a missing+spurious near-match is rejected
        EngineHarness strict = new EngineHarness(new MatchParams(0, 1, 1, 0.99, 1, 0.1));
        strict.addSignatures("snap1", "T", sigs);
        strict.feed(alarm("root", "LOS"), "T", T0);
        strict.feed(alarm("link", "LinkDown"), "T", T0 + 1);
        strict.feed(alarm("noise", "CardFault"), "T", T0 + 2);
        strict.tick(T0 + 3);
        assertThat(strict.results.emitted).isEmpty();

        // low floor 0.2 => the SAME observation now decodes — only the Knowledge floor changed
        EngineHarness lenient = new EngineHarness(new MatchParams(0, 1, 1, 0.2, 1, 0.1));
        lenient.addSignatures("snap1", "T", sigs);
        lenient.feed(alarm("root", "LOS"), "T", T0);
        lenient.feed(alarm("link", "LinkDown"), "T", T0 + 1);
        lenient.feed(alarm("noise", "CardFault"), "T", T0 + 2);
        lenient.tick(T0 + 3);
        assertThat(lenient.results.emitted).hasSize(1);
    }
}
