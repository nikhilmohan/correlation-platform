package com.acp.correlationengine.support;

import com.acp.correlationengine.knowledge.KnowledgeClient;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.knowledge.MatchParams;

/**
 * Test helper producing a {@link KnowledgeParamsProvider} that serves a fixed {@link MatchParams} —
 * so a test controls partial-match tolerance, codebook penalties/floor, and conflict weights
 * explicitly (proving no hard-coded thresholds, AC21). Backed by a stub {@link KnowledgeClient}.
 */
public final class FixedKnowledgeParams {

    private FixedKnowledgeParams() {
    }

    public static KnowledgeParamsProvider provider(MatchParams params) {
        KnowledgeClient client = () -> params;
        KnowledgeParamsProvider provider = new KnowledgeParamsProvider(client, Long.MAX_VALUE);
        provider.bootstrap();
        return provider;
    }

    /** A sensible default param set for tests that do not vary the thresholds. */
    public static MatchParams defaults() {
        return new MatchParams(
                0,     // partialMatchTolerance
                1.0,   // codebookMissingPenalty
                2.0,   // codebookSpuriousPenalty
                0.5,   // codebookScoreFloor
                1.0,   // conflictSpecificityWeight
                0.1);  // conflictConfidenceWeight
    }
}
