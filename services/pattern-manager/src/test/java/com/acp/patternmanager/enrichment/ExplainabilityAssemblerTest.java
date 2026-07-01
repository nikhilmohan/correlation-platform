package com.acp.patternmanager.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.derive.DerivedSessionWindow.WindowType;
import com.acp.patternmanager.rca.RcaResult;
import com.acp.patternmanager.structural.StructuralResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Criterion 4: all required XAI fields present; non-null reason exactly when flag is false. */
class ExplainabilityAssemblerTest {

    private final ExplainabilityAssembler assembler = new ExplainabilityAssembler();

    @Test
    void assemblesAllRequiredXaiFieldsInclStructuralValidation() {
        RcaResult rca = new RcaResult("LOS", null, "unexplained", List.of(), "FiberSpan:1");
        StructuralResult structural = StructuralResult.flag("objects [R7:1] not reachable");
        DerivedSessionWindow window = new DerivedSessionWindow(5000, WindowType.GAP_BASED);
        List<SupportingInstance> instances =
                List.of(new SupportingInstance("w1", "s1", null));
        Map<String, Object> timing = Map.of("timeframeMs", 3000, "medianInterArrivalMs", 1000);

        XaiMetadata xai = assembler.assemble(0.4, 0.9, 3.2, timing, rca, structural, window, instances);

        assertThat(xai.instanceCount()).isPositive();
        assertThat(xai.support()).isEqualTo(0.4);
        assertThat(xai.confidence()).isEqualTo(0.9);
        assertThat(xai.lift()).isEqualTo(3.2);
        assertThat(xai.timing()).containsKeys("timeframeMs", "medianInterArrivalMs");
        assertThat(xai.codebookMatchId()).isNull();
        assertThat(xai.reconcileStatus()).isEqualTo("unexplained");
        assertThat(xai.structurallyValidated()).isFalse();
        assertThat(xai.structuralValidationReason()).isNotNull(); // non-null when false
        assertThat(xai.sessionWindow()).isEqualTo(window);
        assertThat(xai.supportingInstances()).isNotNull();
    }

    @Test
    void reasonNullWhenValidatedTrueAndEmptyInstancesStillCountsOne() {
        RcaResult rca = new RcaResult("LOS", "s99", "confirmed", List.of(), "FiberSpan:1");
        XaiMetadata xai = assembler.assemble(0.4, 0.9, 3.2, Map.of(), rca,
                StructuralResult.pass(), new DerivedSessionWindow(6000, WindowType.FIXED), List.of());

        assertThat(xai.structurallyValidated()).isTrue();
        assertThat(xai.structuralValidationReason()).isNull();
        assertThat(xai.codebookMatchId()).isEqualTo("s99");
        // instanceCount is clamped to >= 1 even with no supporting instances.
        assertThat(xai.instanceCount()).isEqualTo(1);
        assertThat(xai.supportingInstances()).isEmpty();
    }
}
