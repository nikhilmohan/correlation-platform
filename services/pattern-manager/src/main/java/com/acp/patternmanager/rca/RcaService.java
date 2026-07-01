package com.acp.patternmanager.rca;

import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.TopologyClient;
import com.acp.patternmanager.client.dto.TopologyNode;
import com.acp.patternmanager.client.dto.TraversalResult;
import com.acp.patternmanager.reconcile.CodebookMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Root-cause analysis (design "RCA — structural-first ordering + codebook override").
 *
 * <p><b>Graph-ordering rule (default).</b> Resolve each sequence alarm type to a graph object via
 * Topology; order objects by dependency depth (lowest in the dependency graph = most upstream);
 * tie-break by earliest occurrence (the mined sequence is time-ordered, so the earliest is the
 * first token). The alarm type whose object has no upstream dependency within the group — corroborated
 * by earliest position — is the candidate {@code rootCauseAlarmType}.
 *
 * <p><b>Codebook override (authoritative when present).</b> When the sequence overlaps a codebook
 * scenario, the scenario's designated root cause replaces the graph candidate and {@code codebookMatchId}
 * is set (handled by the reconciliation step, which passes the {@link CodebookMatch} in).
 *
 * <p>RCA additionally RETURNS the {@code resolvedObjects} it computed so structural validation reuses
 * them without a second Topology fetch. {@code rootCauseAlarmType} is an {@code alarmType}-vocabulary
 * token (P2-GAP-04): RCA emits the designated alarm's own token verbatim.
 */
@Service
public class RcaService {

    private static final Logger log = LoggerFactory.getLogger(RcaService.class);

    private final TopologyClient topologyClient;

    public RcaService(TopologyClient topologyClient) {
        this.topologyClient = topologyClient;
    }

    /**
     * Perform RCA for a mined sequence, folding in a codebook override when a scenario matched.
     *
     * @param sequence the ordered alarm-type vocabulary tokens
     * @param params Knowledge-sourced RCA / structural params (max-hops for dependency depth probing)
     * @param codebookMatch the reconciliation result (may be empty = no scenario overlap)
     * @return the RCA result incl. the resolved-objects map for structural validation reuse
     */
    public RcaResult analyze(List<String> sequence, EnrichmentParams params,
            Optional<CodebookMatch> codebookMatch) {
        List<ResolvedObject> resolved = resolveObjects(sequence, params);

        // Graph-ordering candidate: lowest dependency depth, tie-break earliest sequence position.
        String graphCandidate = graphOrderingCandidate(sequence, resolved);
        String rootCauseObjectId = objectIdFor(resolved, graphCandidate);

        if (codebookMatch.isPresent() && codebookMatch.get().rootCauseAlarmType() != null) {
            CodebookMatch m = codebookMatch.get();
            log.info("codebook override: rootCauseAlarmType {} -> {} (scenario {})",
                    graphCandidate, m.rootCauseAlarmType(), m.scenarioId());
            String overrideObjectId = objectIdFor(resolved, m.rootCauseAlarmType());
            return new RcaResult(m.rootCauseAlarmType(), m.scenarioId(), m.reconcileStatus(),
                    resolved, overrideObjectId != null ? overrideObjectId : rootCauseObjectId);
        }
        // No codebook match: keep the graph candidate, unexplained.
        return new RcaResult(graphCandidate, null, "unexplained", resolved, rootCauseObjectId);
    }

    private List<ResolvedObject> resolveObjects(List<String> sequence, EnrichmentParams params) {
        List<ResolvedObject> out = new ArrayList<>();
        for (String alarmType : sequence) {
            Optional<TopologyNode> node = topologyClient.getNode(alarmType);
            if (node.isEmpty()) {
                log.warn("RCA: could not resolve alarm type '{}' to a topology object", alarmType);
                out.add(new ResolvedObject(alarmType, null, false, Integer.MAX_VALUE));
                continue;
            }
            String objectId = node.get().managedObjectId();
            int depth = dependencyDepth(objectId, params);
            out.add(new ResolvedObject(alarmType, objectId, true, depth));
        }
        return out;
    }

    /**
     * Bounded dependency depth = number of upstream dependency objects the object still depends on.
     * We probe by traversing dependency edges from the object; an object with no upstream dependency
     * within the group traverses to only itself (depth 0), i.e. it is the root. We approximate the
     * "upstream" count as the count of OTHER resolved objects reachable as dependencies. Lower is
     * more upstream. A resolution/transport failure propagates (retried, never DLQ'd).
     */
    private int dependencyDepth(String objectId, EnrichmentParams params) {
        TraversalResult t = topologyClient.traverse(objectId, List.of(), params.structuralMaxHops());
        // reached excludes the start; the more objects an object depends on downstream, the LOWER
        // it sits (it is upstream of them) — so a large reached set means MORE upstream (depth ~ -size).
        // We invert to a non-negative depth so smaller = more upstream: use MAX - reachedCount is
        // brittle; instead depth = number of edges pointing INTO this object (its own dependencies).
        long upstream = t.edges().stream().filter(e -> objectId.equals(e.to())).count();
        return (int) upstream;
    }

    private String graphOrderingCandidate(List<String> sequence, List<ResolvedObject> resolved) {
        // Prefer the resolved object with the lowest dependency depth; tie-break by earliest
        // position in the ordered mined sequence (index). Unresolved objects fall to the back.
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        int bestIndex = Integer.MAX_VALUE;
        for (ResolvedObject r : resolved) {
            int index = sequence.indexOf(r.alarmType());
            int depth = r.resolved() ? r.dependencyDepth() : Integer.MAX_VALUE;
            if (depth < bestDepth || (depth == bestDepth && index < bestIndex)) {
                best = r.alarmType();
                bestDepth = depth;
                bestIndex = index;
            }
        }
        // If nothing resolved at all, fall back to the earliest-timestamp (first) alarm type.
        if (best == null && !sequence.isEmpty()) {
            best = sequence.get(0);
        }
        return best;
    }

    private static String objectIdFor(List<ResolvedObject> resolved, String alarmType) {
        return resolved.stream()
                .filter(r -> r.alarmType().equals(alarmType) && r.resolved())
                .map(ResolvedObject::managedObjectId)
                .findFirst()
                .orElse(null);
    }
}
