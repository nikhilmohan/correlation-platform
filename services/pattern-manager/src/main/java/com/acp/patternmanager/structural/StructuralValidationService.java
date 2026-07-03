package com.acp.patternmanager.structural;

import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.TopologyClient;
import com.acp.patternmanager.client.dto.TraversalResult;
import com.acp.patternmanager.rca.ResolvedObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Structural validation (design "Structural validation — connected-dependency-path check", OQ-3).
 * Runs AFTER RCA, BEFORE persist, REUSING the {@link ResolvedObject}s RCA already resolved (no
 * redundant Topology resolution). It asks whether the pattern's objects are topologically coherent:
 * every resolved object must be reachable from a single common origin (the RCA root-cause object)
 * within {@code structural.maxHops} (Knowledge-sourced), treating dependency edges as undirected
 * when {@code structural.strictness = lenient} (MVP default) or directed downstream when strict.
 *
 * <p>Connectivity is tested via the SAME {@code GET /topology/traversal} bounded operation RCA uses.
 * MVP policy = FLAG: a failing pattern is persisted with {@code structurallyValidated = false} and a
 * non-null reason (never blocks persistence). Holds NO threshold of its own — all params come from
 * Knowledge (criterion 17).
 */
@Service
public class StructuralValidationService {

    private static final Logger log = LoggerFactory.getLogger(StructuralValidationService.class);

    private final TopologyClient topologyClient;

    public StructuralValidationService(TopologyClient topologyClient) {
        this.topologyClient = topologyClient;
    }

    /**
     * Validate that the RCA-resolved objects form a connected dependency path.
     *
     * @param resolvedObjects the objects RCA resolved (reused; no re-fetch)
     * @param rootCauseObjectId the traversal origin (the RCA root-cause object id); may be null
     * @param params Knowledge-sourced params (max-hops, strictness)
     * @return the structural-validation outcome (flag on failure, MVP policy)
     */
    public StructuralResult validate(List<ResolvedObject> resolvedObjects, String rootCauseObjectId,
            EnrichmentParams params) {
        // Distinct resolved object ids that must all be connected.
        Set<String> targetIds = resolvedObjects.stream()
                .filter(ResolvedObject::resolved)
                .map(ResolvedObject::managedObjectId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Any alarm type that could not be resolved is, by definition, not connected.
        List<String> unresolved = resolvedObjects.stream()
                .filter(r -> !r.resolved())
                .map(ResolvedObject::alarmType)
                .toList();

        // Trivial case: fewer than two distinct resolved objects AND nothing unresolved.
        if (targetIds.size() < 2 && unresolved.isEmpty()) {
            log.info("structural validation: trivially connected ({} resolved object)", targetIds.size());
            return StructuralResult.pass();
        }

        String origin = rootCauseObjectId != null ? rootCauseObjectId : targetIds.stream().findFirst().orElse(null);
        if (origin == null) {
            String reason = "no resolvable origin object; unresolved alarm types: " + unresolved;
            log.info("structural validation FLAGGED: {}", reason);
            return StructuralResult.flag(reason);
        }

        // Bounded BFS from the origin over dependency edges (undirected when lenient).
        TraversalResult traversal = topologyClient.traverse(origin, List.of(), params.structuralMaxHops());
        Set<String> visited = new LinkedHashSet<>();
        visited.add(origin);
        traversal.reached().forEach(n -> visited.add(n.managedObjectId()));
        if (!params.structuralDirected()) {
            // Lenient/undirected: an edge in either direction connects; add both endpoints of walked edges.
            traversal.edges().forEach(e -> {
                visited.add(e.from());
                visited.add(e.to());
            });
        }

        Set<String> unreachable = new LinkedHashSet<>(targetIds);
        unreachable.removeAll(visited);

        if (unreachable.isEmpty() && unresolved.isEmpty()) {
            log.info("structural validation PASSED: {} objects connected within {} hops from {}",
                    targetIds.size(), params.structuralMaxHops(), origin);
            return StructuralResult.pass();
        }

        StringBuilder reason = new StringBuilder();
        if (!unreachable.isEmpty()) {
            reason.append("objects ").append(unreachable)
                    .append(" not reachable from root ").append(origin)
                    .append(" within ").append(params.structuralMaxHops()).append(" hops");
        }
        if (!unresolved.isEmpty()) {
            if (reason.length() > 0) {
                reason.append("; ");
            }
            reason.append("unresolved alarm types: ").append(unresolved);
        }
        log.info("structural validation FLAGGED: {}", reason);
        return StructuralResult.flag(reason.toString());
    }
}
