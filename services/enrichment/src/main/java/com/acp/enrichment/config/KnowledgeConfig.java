package com.acp.enrichment.config;

import com.acp.enrichment.knowledge.KnowledgeClient;
import com.acp.enrichment.ruleset.AlarmTypeVocabulary;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Knowledge integration point — the authoritative source of the {@code alarmTypeVocabulary} value
 * space used to validate ruleset {@code alarmTypeMap} tokens. Config-switchable base URL + a
 * {@code mock|real} mode (no hard-coded URLs), mirroring the Trail Builder integration point. In
 * both modes the SAME {@link KnowledgeClient} calls the frozen list path
 * {@code GET /domains/{domain}/alarm-type-vocabulary}; the mode only changes the base URL (real =
 * the Docker Compose address {@code http://knowledge:8080}; mock = a WireMock/MockWebServer stub
 * generated from Knowledge's published OpenAPI).
 *
 * <p>The {@link AlarmTypeVocabulary} bean is fetched from Knowledge at wiring time, degrading to the
 * documented offline 30-token fallback with a WARNING if Knowledge is unreachable — never silently
 * running on a truncated vocabulary.
 *
 * <p>This introduces NO Knowledge dependency for pipeline params (dedup/flap/self-clear thresholds
 * remain Enrichment-owned ruleset config per the spec's Configuration-ownership invariant); it
 * fetches only the domain vocabulary, which the design states is authored in Knowledge.
 */
@Configuration
public class KnowledgeConfig {

    @Bean
    public RestClient knowledgeRestClient(
            @Value("${knowledge.base-url:http://knowledge:8080}") String baseUrl,
            @Value("${knowledge.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${knowledge.read-timeout-ms:3000}") long readTimeoutMs) {
        // Bounded timeouts so a startup fetch against an unreachable Knowledge degrades to the
        // offline fallback quickly rather than blocking wiring.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    public KnowledgeClient knowledgeClient(RestClient knowledgeRestClient,
            @Value("${enrichment.domain:core-ip}") String domain) {
        return new KnowledgeClient(knowledgeRestClient, domain);
    }

    @Bean
    public AlarmTypeVocabulary alarmTypeVocabulary(KnowledgeClient knowledgeClient) {
        return knowledgeClient.loadVocabularyOrFallback();
    }
}
