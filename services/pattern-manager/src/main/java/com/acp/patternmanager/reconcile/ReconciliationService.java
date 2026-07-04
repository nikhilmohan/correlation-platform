package com.acp.patternmanager.reconcile;

import com.acp.patternmanager.client.CodebookClient;
import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.dto.CodebookDtos.PredictedSymptom;
import com.acp.patternmanager.client.dto.CodebookDtos.ScenarioOut;
import com.acp.patternmanager.client.dto.CodebookDtos.TrailScenarioSignature;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reconciles a mined sequence against the codebook (design task 4/5).
 *
 * <p>Finds a scenario whose predicted symptoms overlap the mined sequence beyond the
 * Knowledge-sourced {@code reconciliation.overlapThreshold} (Jaccard-style overlap over
 * {@code alarmType} tokens — the canonical join key). On a match: {@code CONFIRMED} (full overlap)
 * or {@code MERGED} (partial/complementary), carrying the scenario's designated
 * {@code rootCauseAlarmType} (from the trail signature) and its {@code scenarioId} as
 * {@code codebookMatchId}. On no match: {@code UNEXPLAINED} (no model explanation). Codebook
 * unavailability for the domain is not an error — it yields {@code UNEXPLAINED}.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final CodebookClient codebookClient;

    public ReconciliationService(CodebookClient codebookClient) {
        this.codebookClient = codebookClient;
    }

    /**
     * Match a mined sequence against the codebook.
     *
     * @param sequence the mined alarm-type tokens
     * @param trailId the trail scope from the mined event
     * @param params Knowledge params (overlap threshold)
     * @return the reconciliation outcome (never null)
     */
    public CodebookMatch reconcile(List<String> sequence, String trailId, EnrichmentParams params) {
        Optional<String> codebookId = codebookClient.findCodebookId();
        if (codebookId.isEmpty()) {
            return CodebookMatch.unexplained();
        }
        List<ScenarioOut> scenarios = codebookClient.listScenarios(codebookId.get());
        Set<String> seqTokens = new HashSet<>(sequence);

        ScenarioOut best = null;
        double bestOverlap = 0.0;
        for (ScenarioOut s : scenarios) {
            double overlap = overlapRatio(seqTokens, s.predictedSymptoms());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = s;
            }
        }

        if (best == null || bestOverlap < params.reconciliationOverlapThreshold()) {
            log.info("no codebook scenario overlap >= {} for trail {} (best={})",
                    params.reconciliationOverlapThreshold(), trailId, bestOverlap);
            return CodebookMatch.unexplained();
        }

        String rootCause = designatedRootCause(codebookId.get(), trailId, best);
        String status = bestOverlap >= 1.0 ? "confirmed" : "merged";
        log.info("codebook {} for scenario {} (overlap={}, rootCause={})",
                status, best.scenarioId(), bestOverlap, rootCause);
        return new CodebookMatch(best.scenarioId(), rootCause, status);
    }

    private double overlapRatio(Set<String> seqTokens, List<PredictedSymptom> symptoms) {
        if (symptoms.isEmpty() || seqTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> symptomTokens = new HashSet<>();
        for (PredictedSymptom ps : symptoms) {
            if (ps.alarmType() != null) {
                symptomTokens.add(ps.alarmType());
            }
        }
        if (symptomTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(seqTokens);
        intersection.retainAll(symptomTokens);
        Set<String> union = new HashSet<>(seqTokens);
        union.addAll(symptomTokens);
        return (double) intersection.size() / (double) union.size();
    }

    /**
     * The scenario's designated root-cause alarm type. Prefer the trail signature's explicit
     * {@code rootCauseAlarmType}; fall back to the scenario's fault-origin type (kept as the
     * alarm-type token space).
     */
    private String designatedRootCause(String codebookId, String trailId, ScenarioOut scenario) {
        List<TrailScenarioSignature> sigs = codebookClient.trailSignatures(codebookId, trailId);
        return sigs.stream()
                .filter(s -> scenario.scenarioId().equals(s.scenarioId()))
                .map(TrailScenarioSignature::rootCauseAlarmType)
                .filter(rc -> rc != null && !rc.isBlank())
                .findFirst()
                .orElse(scenario.faultOriginType());
    }
}
