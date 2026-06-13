package com.acp.topology.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.topology.TestFixtures;
import com.acp.topology.config.TopologyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC-23: client built/mocked against Knowledge's frozen {@code GET /domains/{domain}/vocabulary}
 * shape; vocab cached with TTL; fail-closed when unavailable + uncached.
 */
class KnowledgeVocabClientTest {

    private WireMockServer wireMock;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        clock = new MutableClock(Instant.parse("2026-06-08T00:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private KnowledgeVocabClient client(long ttlSeconds) {
        TopologyProperties props = new TopologyProperties();
        props.getKnowledge().setBaseUrl(wireMock.baseUrl());
        props.getKnowledge().setVocabPath("/domains/{domain}/vocabulary");
        props.getKnowledge().setVocabTtlSeconds(ttlSeconds);
        return new KnowledgeVocabClient(props, new ObjectMapper(), clock);
    }

    @Test
    void fetchesAndCachesVocabFromMock() {
        wireMock.stubFor(get(urlEqualTo("/domains/core-ip/vocabulary"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TestFixtures.resource("knowledge/core-ip-vocabulary.json"))));

        KnowledgeVocabClient client = client(300);
        DomainVocabulary first = client.getVocabulary("core-ip");
        assertThat(first.objectTypes()).contains("Node", "Interface", "Site");
        assertThat(first.relations()).contains("HOSTS", "TERMINATES", "LOCATED_AT");
        assertThat(first.version()).isEqualTo("core-ip-v1");

        // Second call within TTL is served from cache (no second HTTP hit).
        client.getVocabulary("core-ip");
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlEqualTo("/domains/core-ip/vocabulary")));
    }

    @Test
    void failsClosedWhenUnavailableAndUncached() {
        wireMock.stubFor(get(urlEqualTo("/domains/core-ip/vocabulary"))
                .willReturn(aResponse().withStatus(503)));
        KnowledgeVocabClient client = client(300);
        assertThatThrownBy(() -> client.getVocabulary("core-ip"))
                .isInstanceOf(VocabularyUnavailableException.class);
    }

    /** Mutable clock for TTL assertions. */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration d) {
            instant = instant.plus(d);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
