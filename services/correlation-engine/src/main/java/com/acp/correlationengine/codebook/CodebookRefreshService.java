package com.acp.correlationengine.codebook;

import com.acp.correlationengine.model.TrailScenarioSignature;
import com.acp.correlationengine.observability.CorrelationMetrics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles a {@code codebook.generated} event: fetches the per-trail signatures for the new
 * {@code codebookId} and installs them into {@link CodebookStore} latest-in-scope per
 * {@code (snapshotId, trailId)} (AC20). On fetch failure the prior codebook is retained and the
 * failure is counted.
 */
public class CodebookRefreshService {

    private static final Logger log = LoggerFactory.getLogger(CodebookRefreshService.class);

    private final CodebookGeneratorClient client;
    private final CodebookStore store;
    private final CorrelationMetrics metrics;

    public CodebookRefreshService(CodebookGeneratorClient client, CodebookStore store,
            CorrelationMetrics metrics) {
        this.client = client;
        this.store = store;
        this.metrics = metrics;
    }

    /** Fetch + install the signatures for {@code codebookId} under {@code snapshotId} scope. */
    public void onCodebookGenerated(String codebookId, String snapshotId) {
        try {
            List<TrailScenarioSignature> signatures = client.fetchTrailSignatures(codebookId);
            Map<String, List<TrailScenarioSignature>> byTrail = signatures.stream()
                    .collect(Collectors.groupingBy(TrailScenarioSignature::trailId));
            byTrail.forEach((trailId, sigs) -> store.replaceScope(snapshotId, trailId, sigs));
            log.info("Codebook {} installed: {} signatures across {} trails",
                    codebookId, signatures.size(), byTrail.size());
        } catch (RuntimeException e) {
            metrics.incrementCodebookFetchFailure();
            log.warn("Codebook fetch failed for {}; retaining prior in-scope codebook", codebookId, e);
        }
    }
}
