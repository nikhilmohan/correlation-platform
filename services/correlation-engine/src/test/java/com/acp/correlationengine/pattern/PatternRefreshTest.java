package com.acp.correlationengine.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
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
 * AC27 — pattern-refresh trail placement comes from the Pattern Manager read API's
 * {@code PatternView.trailId}, not from the event (the frozen {@code PatternApprovedEvent} carries no
 * {@code trailId}). The refresh re-fetches {@code GET /patterns?lifecycle=approved} and places the
 * pattern on the trail from the read API. Backed by a MockWebServer stub of the PatternPage envelope.
 */
class PatternRefreshTest {

    private MockWebServer server;
    private RestPatternManagerClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient http = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new RestPatternManagerClient(http);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void ac27_trailPlacementComesFromReadApiNotEvent() throws InterruptedException {
        // The read API's PatternView carries trailId = "T" (the event would not).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "items": [
                            {
                              "patternId": "P",
                              "trailId": "T",
                              "sequence": ["LOS", "LinkDown"],
                              "rootCauseAlarmType": "LOS",
                              "confidence": 0.87,
                              "sessionWindow": {"windowMs": 45000, "type": "gap-based"}
                            }
                          ],
                          "total": 1, "limit": 50, "offset": 0
                        }
                        """));

        PatternStore store = new PatternStore();
        PatternRefreshService refresh = new PatternRefreshService(client, store);
        refresh.refreshOnApproval(); // triggered by a patterns.approved event

        List<PatternRef> active = store.activePatternsOn("T");
        assertThat(active).hasSize(1);
        PatternRef p = active.get(0);
        assertThat(p.patternId()).isEqualTo("P");
        assertThat(p.trailId()).isEqualTo("T"); // from PatternView.trailId
        assertThat(p.windowMs()).isEqualTo(45000);
        assertThat(p.windowType()).isEqualTo(WindowType.GAP_BASED);
        assertThat(store.hasPatternsOn("T")).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/patterns?lifecycle=approved");
    }
}
