package com.acp.correlationengine.generalize;

import com.acp.correlationengine.observability.CorrelationMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Real (and mock-endpoint) {@link TrailBuilderClient} over Trail Builder's frozen read API:
 * {@code GET /trails?snapshotId&domain&limit&offset} ({@code ListTrailsResponse}) to enumerate trail
 * ids, and {@code GET /trails/{trailId}} ({@code TrailDetail}) for each trail's member
 * {@code objectType}s. Same code path in {@code mock} (WireMock/MockWebServer stub generated from the
 * published {@code openapi.json}) and {@code real} (compose {@code trail-builder}) modes — only the
 * base URL differs (spec AC42).
 *
 * <p>Per-trail member fetch is retried a bounded number of times; on exhaustion it yields
 * {@link Optional#empty()} so the trail is absent from the index (AC41). The enumerate call
 * propagates its failure so the caller can retain the last-good index.
 */
public class RestTrailBuilderClient implements TrailBuilderClient {

    private static final Logger log = LoggerFactory.getLogger(RestTrailBuilderClient.class);
    private static final int PAGE_LIMIT = 1000;

    private final RestClient http;
    private final int maxRetries;
    private final CorrelationMetrics metrics;

    public RestTrailBuilderClient(RestClient http, int maxRetries, CorrelationMetrics metrics) {
        this.http = http;
        this.maxRetries = Math.max(0, maxRetries);
        this.metrics = metrics;
    }

    @Override
    public List<String> listTrailIds(String snapshotId, String domain) {
        List<String> ids = new ArrayList<>();
        int offset = 0;
        while (true) {
            final int off = offset;
            JsonNode body = http.get()
                    .uri(b -> b.path("/trails")
                            .queryParam("snapshotId", snapshotId)
                            .queryParam("domain", domain)
                            .queryParam("limit", PAGE_LIMIT)
                            .queryParam("offset", off)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode trails = body == null ? null : body.get("trails");
            if (trails == null || !trails.isArray() || trails.isEmpty()) {
                break;
            }
            for (JsonNode t : trails) {
                JsonNode id = t.get("trailId");
                if (id != null && !id.isNull()) {
                    ids.add(id.asText());
                }
            }
            if (trails.size() < PAGE_LIMIT) {
                break; // last page
            }
            offset += PAGE_LIMIT;
        }
        return ids;
    }

    @Override
    public Optional<Set<String>> getTrailMemberTypes(String trailId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                JsonNode detail = http.get()
                        .uri("/trails/{trailId}", trailId)
                        .retrieve()
                        .body(JsonNode.class);
                Set<String> types = new LinkedHashSet<>();
                JsonNode members = detail == null ? null : detail.get("members");
                if (members != null && members.isArray()) {
                    for (JsonNode m : members) {
                        JsonNode ot = m.get("objectType");
                        if (ot != null && !ot.isNull() && !ot.asText().isBlank()) {
                            types.add(ot.asText());
                        }
                    }
                }
                return Optional.of(types);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        metrics.incrementTrailBuilderFetchError();
        log.warn("Trail Builder fetch failed for trail {} after {} attempt(s); trail absent from index",
                trailId, maxRetries + 1, last);
        return Optional.empty();
    }
}
