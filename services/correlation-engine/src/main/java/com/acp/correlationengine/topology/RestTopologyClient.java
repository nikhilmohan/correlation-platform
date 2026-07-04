package com.acp.correlationengine.topology;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Real (and mock-endpoint) {@link TopologyClient} over Topology's frozen read API:
 * {@code GET /topology/snapshots} -> {@code {snapshots:[{snapshotId,status,domain,...}]}}. Picks the
 * entry with {@code status == "current"} for the configured domain. Same code path in {@code mock}
 * (stub generated from the published {@code openapi.json}) and {@code real} (compose {@code topology})
 * modes — only the base URL differs.
 *
 * <p>Resilience: any transport/parse failure yields {@link Optional#empty()} so startup falls back to
 * the approved-pattern snapshot source (and, failing that, the live {@code trails.built} path) rather
 * than crashing bootstrap.
 */
public class RestTopologyClient implements TopologyClient {

    private static final Logger log = LoggerFactory.getLogger(RestTopologyClient.class);
    private static final String STATUS_CURRENT = "current";

    private final RestClient http;

    public RestTopologyClient(RestClient http) {
        this.http = http;
    }

    @Override
    public Optional<String> currentSnapshotId(String domain) {
        try {
            JsonNode body = http.get()
                    .uri("/topology/snapshots")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode snapshots = body == null ? null : body.get("snapshots");
            if (snapshots == null || !snapshots.isArray()) {
                return Optional.empty();
            }
            for (JsonNode s : snapshots) {
                if (!STATUS_CURRENT.equalsIgnoreCase(text(s, "status"))) {
                    continue;
                }
                String snapDomain = text(s, "domain");
                if (domain != null && snapDomain != null && !domain.equalsIgnoreCase(snapDomain)) {
                    continue; // a current snapshot, but for a different domain
                }
                String snapshotId = text(s, "snapshotId");
                if (snapshotId != null && !snapshotId.isBlank()) {
                    return Optional.of(snapshotId);
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Topology current-snapshot discovery failed (domain={}); "
                    + "will try the approved-pattern fallback", domain, e);
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
