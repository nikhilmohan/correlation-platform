package com.acp.patternmanager.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.client.CodebookClient;
import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.dto.CodebookDtos.PredictedSymptom;
import com.acp.patternmanager.client.dto.CodebookDtos.ScenarioOut;
import com.acp.patternmanager.client.dto.CodebookDtos.TrailScenarioSignature;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Codebook reconciliation (criteria 2, 3). */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private CodebookClient codebookClient;

    private ReconciliationService svc;

    private final EnrichmentParams params =
            new EnrichmentParams(4, "lenient", "flag", 1.0, 0.5, 0.5);

    @BeforeEach
    void setUp() {
        svc = new ReconciliationService(codebookClient);
    }

    // Criterion 3: no codebook match -> unexplained, codebookMatchId null (lift preserved elsewhere).
    @Test
    void noCodebookMatchFlagsUnexplainedPreservesLift() {
        when(codebookClient.findCodebookId()).thenReturn(Optional.of("cb-1"));
        // A scenario whose symptoms don't overlap the mined sequence at all.
        when(codebookClient.listScenarios("cb-1")).thenReturn(List.of(
                new ScenarioOut("s1", "obj:1", "SomethingElse",
                        List.of(new PredictedSymptom("Unrelated", "x:1")), List.of("trail-1"))));

        CodebookMatch match = svc.reconcile(List.of("LOS", "LinkDown"), "trail-1", params);

        assertThat(match.matched()).isFalse();
        assertThat(match.scenarioId()).isNull();
        assertThat(match.reconcileStatus()).isEqualTo("unexplained");
    }

    // Criterion 2: an overlapping scenario -> match with the designated rootCauseAlarmType + scenarioId.
    @Test
    void overlappingScenarioMatchesWithDesignatedRootCause() {
        when(codebookClient.findCodebookId()).thenReturn(Optional.of("cb-1"));
        when(codebookClient.listScenarios("cb-1")).thenReturn(List.of(
                new ScenarioOut("scenario-lc", "LineCard:1", "LineCardFault",
                        List.of(new PredictedSymptom("LOS", "x:1"),
                                new PredictedSymptom("LinkDown", "y:1")),
                        List.of("trail-1"))));
        when(codebookClient.trailSignatures("cb-1", "trail-1")).thenReturn(List.of(
                new TrailScenarioSignature("trail-1", "scenario-lc", "LineCardFault", List.of())));

        CodebookMatch match = svc.reconcile(List.of("LOS", "LinkDown"), "trail-1", params);

        assertThat(match.matched()).isTrue();
        assertThat(match.scenarioId()).isEqualTo("scenario-lc");
        assertThat(match.rootCauseAlarmType()).isEqualTo("LineCardFault");
        assertThat(match.reconcileStatus()).isEqualTo("confirmed"); // full overlap
    }

    // No codebook available for the domain -> unexplained (not an error).
    @Test
    void noCodebookAvailableIsUnexplained() {
        when(codebookClient.findCodebookId()).thenReturn(Optional.empty());
        CodebookMatch match = svc.reconcile(List.of("LOS"), "trail-1", params);
        assertThat(match.reconcileStatus()).isEqualTo("unexplained");
    }
}
