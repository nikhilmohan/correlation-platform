package com.acp.patternmanager.enrichment;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import com.acp.patternmanager.rca.RcaResult;
import com.acp.patternmanager.structural.StructuralResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link XaiMetadata} value object (design task 6) from the mined metrics/timing, the
 * RCA/reconciliation result, the structural-validation status, the derived session window, and the
 * supporting instances. Guarantees {@code instanceCount > 0} and a non-null structural reason exactly
 * when the flag is false (criterion 4).
 */
@Component
public class ExplainabilityAssembler {

    /**
     * @param support mined support
     * @param confidence mined confidence
     * @param lift mined lift
     * @param timing descriptive mined timing (unchanged)
     * @param rca the RCA/reconciliation result
     * @param structural the structural-validation outcome
     * @param sessionWindow the derived session window
     * @param supportingInstances example occurrences from provenance (may be empty)
     * @return the assembled XAI metadata
     */
    public XaiMetadata assemble(double support, double confidence, double lift,
            Map<String, Object> timing, RcaResult rca, StructuralResult structural,
            DerivedSessionWindow sessionWindow, List<SupportingInstance> supportingInstances) {
        int instanceCount = Math.max(1, supportingInstances.size());
        return new XaiMetadata(
                instanceCount,
                support,
                confidence,
                lift,
                timing,
                rca.codebookMatchId(),
                rca.reconcileStatus(),
                structural.structurallyValidated(),
                structural.reason(),
                sessionWindow,
                supportingInstances);
    }
}
