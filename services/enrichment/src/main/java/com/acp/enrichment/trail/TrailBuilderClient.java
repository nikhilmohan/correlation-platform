package com.acp.enrichment.trail;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Client for the Trail Builder's <b>frozen</b> {@code getTrailsForObject} contract:
 *
 * <pre>GET /trails/by-object?managedObjectId={moId}&amp;domain={domain}
 *   -&gt; { managedObjectId, domain, trailIds: string[] }</pre>
 *
 * <p>The path has <b>NO {@code /api/v1} prefix</b> and the service listens on container port 8000
 * (in-cluster {@code http://trail-builder:8000}); both are verified against Trail Builder's
 * published OpenAPI by {@code TrailBuilderClientContractTest}. The base URL and {@code mock|real}
 * mode are config-switchable ({@code TRAIL_BUILDER_BASE_URL}, {@code TRAIL_BUILDER_MODE}); unit
 * tests point the same client at a WireMock stub generated from that OpenAPI.
 *
 * <p>Wrapped with Resilience4j retry + circuit-breaker; after the retries are exhausted the caller
 * routes the alarm to the input DLQ (design open question #42).
 */
public class TrailBuilderClient {

    private static final Logger log = LoggerFactory.getLogger(TrailBuilderClient.class);

    /** The frozen sub-resource path (no {@code /api/v1} prefix). */
    public static final String BY_OBJECT_PATH = "/trails/by-object";

    private final RestClient restClient;

    public TrailBuilderClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @param managedObjectId the survivor alarm's managed object
     * @param domain the configured domain ({@code ENRICHMENT_DOMAIN})
     * @return the trail ids for the object (possibly empty)
     * @throws TrailLookupException after retries/circuit-breaker are exhausted
     */
    @Retry(name = "trailBuilder")
    @CircuitBreaker(name = "trailBuilder")
    public List<String> getTrailsForObject(String managedObjectId, String domain) {
        try {
            TrailsForObjectResponse body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(BY_OBJECT_PATH)
                            .queryParam("managedObjectId", managedObjectId)
                            .queryParam("domain", domain)
                            .build())
                    .retrieve()
                    .body(TrailsForObjectResponse.class);
            if (body == null || body.trailIds() == null) {
                return List.of();
            }
            return body.trailIds();
        } catch (RuntimeException e) {
            log.warn("trail lookup failed for {} domain={}: {}", managedObjectId, domain,
                    e.getMessage());
            throw e;
        }
    }
}
