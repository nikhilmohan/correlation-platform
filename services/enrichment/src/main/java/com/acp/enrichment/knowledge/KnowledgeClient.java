package com.acp.enrichment.knowledge;

import com.acp.enrichment.ruleset.AlarmTypeVocabulary;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * Read-only client for the Knowledge Service's <b>frozen</b> domain-vocabulary contract, used to
 * fetch the authoritative {@code alarmTypeVocabulary} at startup so ruleset validation is driven by
 * the single source of truth (Knowledge) rather than a hard-coded set.
 *
 * <pre>GET /domains/{domain}/alarm-type-vocabulary
 *   -&gt; [ { recordId, isCurrent, payload: { alarmTypes: [ ... ] } }, ... ]</pre>
 *
 * <p><b>Path contract.</b> {@code alarm-type-vocabulary} is a Knowledge <i>recordType</i> served by
 * the generic list endpoint {@code GET /domains/{domain}/{recordType}} (NO {@code /api/v1} prefix);
 * the response is a LIST of {@code RecordResponse} envelopes, each with a {@code payload}. Enrichment
 * reads the current (or first) element's {@code payload.alarmTypes[]}. This path and the
 * envelope-vs-payload shape are verified against Knowledge's published OpenAPI by
 * {@code KnowledgeClientContractTest} (the noise-filter lesson: the client path must match a path
 * the service actually serves, and consumers must read {@code .payload}, not the top level).
 *
 * <p>This is the domain <b>vocabulary</b> (the {@code alarmType} value space), NOT pipeline tuning
 * params — Enrichment remains Knowledge-free for dedup/flap/self-clear thresholds (spec
 * "Configuration ownership invariant"). Fetching the vocabulary here is consistent with the design's
 * statement that the {@code alarmTypeVocabulary} value space is authored in Knowledge.
 */
public class KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);

    /** The frozen recordType path segment (kebab). */
    public static final String ALARM_TYPE_VOCABULARY_RECORD_TYPE = "alarm-type-vocabulary";
    /** The vocabulary array field inside the record {@code payload}. */
    public static final String ALARM_TYPES_FIELD = "alarmTypes";

    private final RestClient restClient;
    private final String domain;

    public KnowledgeClient(RestClient restClient, String domain) {
        this.restClient = restClient;
        this.domain = domain;
    }

    /**
     * Fetch the authoritative alarm-type vocabulary for the configured domain.
     *
     * @return the vocabulary tokens
     * @throws KnowledgeUnavailableException if Knowledge is unreachable, returns no
     *     alarm-type-vocabulary record, or the record carries no {@code alarmTypes}
     */
    public Set<String> fetchAlarmTypeVocabulary() {
        List<KnowledgeRecordResponse> records;
        try {
            records = restClient.get()
                    .uri("/domains/{domain}/{recordType}", domain, ALARM_TYPE_VOCABULARY_RECORD_TYPE)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<KnowledgeRecordResponse>>() {});
        } catch (RuntimeException e) {
            throw new KnowledgeUnavailableException(
                    "failed to fetch alarm-type-vocabulary from Knowledge for domain '" + domain
                            + "': " + e.getMessage(), e);
        }
        if (records == null || records.isEmpty()) {
            throw new KnowledgeUnavailableException(
                    "Knowledge returned no alarm-type-vocabulary record for domain '" + domain + "'");
        }
        // Prefer the current version; fall back to the first element.
        KnowledgeRecordResponse chosen = records.stream()
                .filter(r -> Boolean.TRUE.equals(r.isCurrent()))
                .findFirst()
                .orElse(records.get(0));
        Set<String> tokens = readTokens(chosen);
        if (tokens.isEmpty()) {
            throw new KnowledgeUnavailableException(
                    "Knowledge alarm-type-vocabulary payload for domain '" + domain
                            + "' has no '" + ALARM_TYPES_FIELD + "' tokens");
        }
        log.info("loaded {} alarm-type vocabulary tokens from Knowledge (domain={})", tokens.size(),
                domain);
        return tokens;
    }

    private static Set<String> readTokens(KnowledgeRecordResponse record) {
        Set<String> tokens = new LinkedHashSet<>();
        JsonNode payload = record.payload();
        if (payload == null) {
            return tokens;
        }
        JsonNode arr = payload.get(ALARM_TYPES_FIELD);
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual() && !n.asText().isBlank()) {
                    tokens.add(n.asText());
                }
            }
        }
        return tokens;
    }

    /**
     * Fetch the vocabulary, degrading to the documented offline fallback (the full 30-token Core IP
     * set) with a WARNING when Knowledge is unavailable — never silently running on a truncated set.
     *
     * @return a vocabulary built from Knowledge, or the offline fallback on failure
     */
    public AlarmTypeVocabulary loadVocabularyOrFallback() {
        try {
            return new AlarmTypeVocabulary(fetchAlarmTypeVocabulary());
        } catch (KnowledgeUnavailableException e) {
            log.warn("Knowledge alarm-type-vocabulary unavailable at startup; falling back to the "
                    + "built-in {}-token Core IP set. Reason: {}",
                    AlarmTypeVocabulary.CORE_IP_FALLBACK.size(), e.getMessage());
            return AlarmTypeVocabulary.coreIpFallback();
        }
    }
}
