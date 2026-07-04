package com.acp.correlationengine.codebook;

import com.acp.correlationengine.model.TrailScenarioSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trail-scoped codebook scenario signatures, latest-in-scope per {@code (snapshotId, trailId)}.
 * When a newer {@code codebook.generated} event arrives for the same scope, its signatures replace
 * the prior ones for all subsequent instance evaluations (AC20).
 */
public class CodebookStore {

    /** (snapshotId :: trailId) -> signatures for that scope */
    private final Map<String, List<TrailScenarioSignature>> byScope = new ConcurrentHashMap<>();

    private static String scope(String snapshotId, String trailId) {
        return snapshotId + "::" + trailId;
    }

    /**
     * Latest-in-scope replace: install {@code signatures} for {@code (snapshotId, trailId)},
     * discarding whatever was held for that exact scope before.
     */
    public void replaceScope(String snapshotId, String trailId, List<TrailScenarioSignature> signatures) {
        byScope.put(scope(snapshotId, trailId), List.copyOf(signatures));
    }

    /** @return all scenario signatures currently active for {@code trailId} across all snapshots. */
    public List<TrailScenarioSignature> signaturesForTrail(String trailId) {
        List<TrailScenarioSignature> out = new ArrayList<>();
        String suffix = "::" + trailId;
        byScope.forEach((k, v) -> {
            if (k.endsWith(suffix)) {
                out.addAll(v);
            }
        });
        return out;
    }
}
