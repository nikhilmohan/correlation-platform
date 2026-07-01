package com.acp.patternmanager.client;

import com.acp.patternmanager.client.dto.CodebookDtos.CodebookListResponse;
import com.acp.patternmanager.client.dto.CodebookDtos.ScenarioListResponse;
import com.acp.patternmanager.client.dto.CodebookDtos.ScenarioOut;
import com.acp.patternmanager.client.dto.CodebookDtos.TrailScenarioSignature;
import com.acp.patternmanager.client.dto.CodebookDtos.TrailSignaturesResponse;
import com.acp.patternmanager.config.IntegrationProperties;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;

/**
 * Client for the Codebook Generator read API — reconciliation + RCA override.
 *
 * <p><b>Real verified paths (no {@code /api/v1}).</b>
 * <ul>
 *   <li>{@code GET /codebooks?domain={domain}} — list codebooks for the domain.
 *   <li>{@code GET /codebooks/{codebookId}/scenarios} — the scenarios to test sequence overlap on
 *       ({@code predictedSymptoms[].alarmType}).
 *   <li>{@code GET /codebooks/{codebookId}/trail-signatures?trailId={trailId}} — the trail-scoped
 *       signatures carrying the scenario's designated {@code rootCauseAlarmType} (the RCA-override value).
 * </ul>
 */
@Component
public class CodebookClient {

    private static final Logger log = LoggerFactory.getLogger(CodebookClient.class);

    private final RestClient restClient;
    private final String domain;

    public CodebookClient(RestClient codebookRestClient, IntegrationProperties integration) {
        this.restClient = codebookRestClient;
        String cfgDomain = integration.codebook() != null ? integration.codebook().domain() : null;
        this.domain = cfgDomain != null ? cfgDomain : "core-ip";
    }

    /**
     * The first codebook available for the domain (the active/current one), if any.
     *
     * @return the codebookId, or empty if the domain has no codebook
     */
    public Optional<String> findCodebookId() {
        CodebookListResponse resp = restClient.get()
                .uri("/codebooks?domain={domain}", domain)
                .retrieve()
                .body(CodebookListResponse.class);
        if (resp == null || resp.codebooks().isEmpty()) {
            log.info("no codebook available for domain={}", domain);
            return Optional.empty();
        }
        return resp.codebooks().stream()
                .filter(c -> Boolean.TRUE.equals(c.active()))
                .map(com.acp.patternmanager.client.dto.CodebookDtos.CodebookMeta::codebookId)
                .findFirst()
                .or(() -> Optional.ofNullable(resp.codebooks().get(0).codebookId()));
    }

    /**
     * List the scenarios of a codebook (for sequence-overlap testing).
     *
     * @param codebookId the codebook id
     * @return the scenarios (possibly empty)
     */
    public List<ScenarioOut> listScenarios(String codebookId) {
        ScenarioListResponse resp = restClient.get()
                .uri("/codebooks/{codebookId}/scenarios", codebookId)
                .retrieve()
                .body(ScenarioListResponse.class);
        return resp != null ? resp.scenarios() : List.of();
    }

    /**
     * The trail-scoped scenario signatures (carry the designated {@code rootCauseAlarmType}).
     *
     * @param codebookId the codebook id
     * @param trailId the trail scope from the mined event
     * @return the signatures (possibly empty)
     */
    public List<TrailScenarioSignature> trailSignatures(String codebookId, String trailId) {
        try {
            TrailSignaturesResponse resp = restClient.get()
                    .uri("/codebooks/{codebookId}/trail-signatures?trailId={trailId}",
                            codebookId, trailId)
                    .retrieve()
                    .body(TrailSignaturesResponse.class);
            return resp != null ? resp.signatures() : List.of();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw e;
        }
    }
}
