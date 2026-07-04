package com.acp.correlationengine.codebook;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.TrailScenarioSignature;
import com.acp.correlationengine.observability.CorrelationMetrics;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * AC23 — the codebook client fetches the trail-signatures projection and the mapper parses per-trail
 * {@code TrailScenarioSignature {trailId, scenarioId, rootCauseAlarmType, expectedSymptoms[{alarmType,
 * managedObjectId}]}}, carrying the codebook artifact id onto each signature. Backed by a
 * MockWebServer stub of the Codebook Generator's published projection shape.
 */
class CodebookClientTest {

    private MockWebServer server;
    private RestCodebookGeneratorClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient http = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new RestCodebookGeneratorClient(http);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void ac23_fetchesTrailSignaturesProjection() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "codebookId": "CODEBOOK-7",
                          "domain": "core-ip",
                          "trailSignatures": [
                            {
                              "trailId": "T1",
                              "scenarioId": "S1",
                              "rootCauseAlarmType": "LOS",
                              "expectedSymptoms": [
                                {"alarmType": "LOS", "managedObjectId": "router:R1"},
                                {"alarmType": "LinkDown", "managedObjectId": "port:P2"}
                              ]
                            }
                          ]
                        }
                        """));

        List<TrailScenarioSignature> sigs = client.fetchTrailSignatures("CODEBOOK-7");

        assertThat(sigs).hasSize(1);
        TrailScenarioSignature s = sigs.get(0);
        assertThat(s.codebookId()).isEqualTo("CODEBOOK-7"); // artifact id carried onto the signature
        assertThat(s.trailId()).isEqualTo("T1");
        assertThat(s.scenarioId()).isEqualTo("S1");
        assertThat(s.rootCauseAlarmType()).isEqualTo("LOS");
        assertThat(s.expectedAlarmTypes()).containsExactly("LOS", "LinkDown");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/codebooks/CODEBOOK-7/trail-signatures");
    }

    /** AC20 — a newer codebook.generated for the same scope replaces the prior signatures. */
    @Test
    void ac20_latestCodebookReplacesPrior() {
        CodebookStore store = new CodebookStore();
        CodebookRefreshService refresh = new CodebookRefreshService(
                cbId -> List.of(new TrailScenarioSignature(cbId, "T1", "S1", "LOS", List.of())),
                store, CorrelationMetrics.NOOP);

        refresh.onCodebookGenerated("CODEBOOK-V1", "snap1");
        assertThat(store.signaturesForTrail("T1")).extracting(TrailScenarioSignature::codebookId)
                .containsExactly("CODEBOOK-V1");

        refresh.onCodebookGenerated("CODEBOOK-V2", "snap1"); // same scope -> replace
        assertThat(store.signaturesForTrail("T1")).extracting(TrailScenarioSignature::codebookId)
                .containsExactly("CODEBOOK-V2");
    }
}
