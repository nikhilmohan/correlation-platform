package com.acp.patternmanager.client;

import com.acp.patternmanager.client.dto.TopologyNode;
import com.acp.patternmanager.client.dto.TraversalResult;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;

/**
 * Client for the Topology Service — the SAME integration point used by BOTH RCA (object resolution
 * + bounded dependency traversal) and structural validation (connectivity check). Reads via the
 * Topology API only; never touches the topology graph directly.
 *
 * <p><b>Real verified paths (no {@code /api/v1}).</b>
 * <ul>
 *   <li>{@code GET /topology/nodes/{managedObjectId}} — resolve an object.
 *   <li>{@code GET /topology/traversal?start={id}&relation={rel}&maxDepth={n}} — bounded dependency
 *       traversal. NOTE the real query params are {@code start}, {@code relation} (repeated array),
 *       {@code maxDepth} (verified against the live Topology OpenAPI), not {@code edgeType}/{@code depth}.
 * </ul>
 */
@Component
public class TopologyClient {

    private static final Logger log = LoggerFactory.getLogger(TopologyClient.class);

    private final RestClient restClient;

    public TopologyClient(RestClient topologyRestClient) {
        this.restClient = topologyRestClient;
    }

    /**
     * Resolve a managed object by its id. A 404 means the object does not exist in the graph.
     *
     * @param managedObjectId canonical {@code <objectType>:<id>} id
     * @return the node, or empty if not found (404)
     */
    public Optional<TopologyNode> getNode(String managedObjectId) {
        try {
            // managedObjectId is `<objectType>:<id>`; passed as a URI template variable so Spring
            // percent-encodes it once (the ':' becomes %3A on the wire, which the Topology service's
            // {managedObjectId} path variable decodes back to ':' server-side).
            TopologyNode node = restClient.get()
                    .uri("/topology/nodes/{id}", managedObjectId)
                    .retrieve()
                    .body(TopologyNode.class);
            return Optional.ofNullable(node);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.warn("topology node not found: {}", managedObjectId);
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Bounded dependency traversal from a start object over the given dependency relations.
     *
     * @param start the start managedObjectId
     * @param relations the dependency relation types to traverse (may be empty = all relations)
     * @param maxDepth the depth bound (from Knowledge {@code structural.maxHops})
     * @return the reached set + edges walked
     */
    public TraversalResult traverse(String start, List<String> relations, int maxDepth) {
        StringBuilder query = new StringBuilder("/topology/traversal?start={start}&maxDepth={maxDepth}");
        List<Object> vars = new java.util.ArrayList<>();
        vars.add(start);
        vars.add(maxDepth);
        if (relations != null) {
            for (String rel : relations) {
                query.append("&relation={relation").append(vars.size()).append("}");
                vars.add(rel);
            }
        }
        TraversalResult result = restClient.get()
                .uri(query.toString(), vars.toArray())
                .retrieve()
                .body(TraversalResult.class);
        return result != null ? result : new TraversalResult(start, maxDepth, List.of(), List.of());
    }
}
