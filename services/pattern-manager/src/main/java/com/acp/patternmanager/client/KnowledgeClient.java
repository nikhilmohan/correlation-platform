package com.acp.patternmanager.client;

import com.acp.patternmanager.config.IntegrationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads RCA / structural-validation / reconciliation params from the Knowledge Service.
 *
 * <p><b>Real verified path (no {@code /api/v1}).</b>
 * {@code GET /domains/{domain}/model-params/{recordId}} where {@code domain = core-ip},
 * the recordType path segment is the kebab-case {@code model-params}, and {@code recordId} is the
 * PERCENT-ENCODED record id {@code core-ip/modelParams/pattern-manager} ->
 * {@code core-ip%2FmodelParams%2Fpattern-manager} (it contains slashes).
 *
 * <p><b>Envelope, not flat.</b> The response is a {@code RecordResponse} ENVELOPE; the params live
 * at {@code payload.params[]} as a list of {@code {key, value}} entries with DOTTED keys
 * ({@code structural.maxHops}, {@code structural.strictness}, {@code structural.flagVsReject},
 * {@code rca.dependencyOrderingWeight}, {@code rca.timestampWeight},
 * {@code reconciliation.overlapThreshold}). This client unwraps {@code .payload} and flattens
 * {@code params[]} into a typed {@link EnrichmentParams} — no threshold is hard-coded.
 */
@Component
public class KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);

    /** The Knowledge model-params record id for this service (contains slashes -> percent-encoded). */
    static final String RECORD_ID = "core-ip/modelParams/pattern-manager";
    static final String RECORD_TYPE_PATH_SEGMENT = "model-params";

    private final RestClient restClient;
    private final String domain;

    public KnowledgeClient(RestClient knowledgeRestClient, IntegrationProperties integration) {
        this.restClient = knowledgeRestClient;
        String cfgDomain = integration.knowledge() != null ? integration.knowledge().domain() : null;
        this.domain = cfgDomain != null ? cfgDomain : "core-ip";
    }

    /**
     * Fetch and flatten the pattern-manager model params.
     *
     * @return the typed enrichment params
     * @throws org.springframework.web.client.RestClientException on transport / non-2xx (a valid
     *     event whose Knowledge call fails is retried, never DLQ'd — see the consumer)
     * @throws IllegalStateException if the envelope is missing {@code payload.params}
     */
    public EnrichmentParams fetchEnrichmentParams() {
        // The recordId contains slashes and must arrive percent-encoded (%2F) so the Knowledge
        // controller URL-decodes it back to `core-ip/modelParams/pattern-manager`. Pass it as a URI
        // TEMPLATE VARIABLE so Spring encodes the slashes exactly once (a pre-encoded string would be
        // double-encoded to %252F).
        JsonNode envelope = restClient.get()
                .uri("/domains/{domain}/{recordType}/{recordId}",
                        domain, RECORD_TYPE_PATH_SEGMENT, RECORD_ID)
                .retrieve()
                .body(JsonNode.class);

        if (envelope == null || envelope.path("payload").path("params").isMissingNode()) {
            throw new IllegalStateException(
                    "Knowledge model-params response missing payload.params for " + RECORD_ID);
        }
        JsonNode params = envelope.get("payload").get("params");
        return flatten(params);
    }

    private EnrichmentParams flatten(JsonNode params) {
        int maxHops = 0;
        String strictness = null;
        String flagVsReject = null;
        double depWeight = 0.0;
        double tsWeight = 0.0;
        double overlap = 0.0;
        boolean seenMaxHops = false, seenStrict = false, seenFlag = false,
                seenDep = false, seenTs = false, seenOverlap = false;

        for (JsonNode p : params) {
            String key = p.path("key").asText(null);
            JsonNode value = p.get("value");
            if (key == null || value == null) {
                continue;
            }
            switch (key) {
                case "structural.maxHops" -> { maxHops = value.asInt(); seenMaxHops = true; }
                case "structural.strictness" -> { strictness = value.asText(); seenStrict = true; }
                case "structural.flagVsReject" -> { flagVsReject = value.asText(); seenFlag = true; }
                case "rca.dependencyOrderingWeight" -> { depWeight = value.asDouble(); seenDep = true; }
                case "rca.timestampWeight" -> { tsWeight = value.asDouble(); seenTs = true; }
                case "reconciliation.overlapThreshold" -> { overlap = value.asDouble(); seenOverlap = true; }
                default -> { /* forward-compatible: ignore unknown params */ }
            }
        }
        if (!(seenMaxHops && seenStrict && seenFlag && seenDep && seenTs && seenOverlap)) {
            throw new IllegalStateException("Knowledge model-params for " + RECORD_ID
                    + " is missing one or more required params (structural.maxHops, "
                    + "structural.strictness, structural.flagVsReject, rca.dependencyOrderingWeight, "
                    + "rca.timestampWeight, reconciliation.overlapThreshold)");
        }
        log.debug("resolved Knowledge params: maxHops={} strictness={} flagVsReject={} "
                        + "depWeight={} tsWeight={} overlap={}",
                maxHops, strictness, flagVsReject, depWeight, tsWeight, overlap);
        return new EnrichmentParams(maxHops, strictness, flagVsReject, depWeight, tsWeight, overlap);
    }
}
