package com.acp.correlationengine.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link RestTopologyClient} tests, backed by a MockWebServer stub returning Topology's published
 * {@code GET /topology/snapshots} shape ({@code {snapshots:[{snapshotId,status,domain}]}}). Verifies
 * the {@code status == "current"} pick, domain filtering, and fail-safe empty on error — the ONE
 * Topology read used for startup snapshot discovery.
 */
class RestTopologyClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RestTopologyClient client() {
        RestClient http = RestClient.builder().baseUrl(server.url("/").toString()).build();
        return new RestTopologyClient(http);
    }

    @Test
    void picksTheCurrentSnapshotForDomain() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {
                  "snapshots": [
                    {"snapshotId": "SNAP-OLD", "status": "superseded", "domain": "core-ip"},
                    {"snapshotId": "SNAP-CURRENT", "status": "current", "domain": "core-ip"}
                  ]
                }
                """));

        Optional<String> snap = client().currentSnapshotId("core-ip");

        assertThat(snap).contains("SNAP-CURRENT");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/topology/snapshots");
    }

    @Test
    void ignoresCurrentSnapshotOfAnotherDomain() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"snapshots": [{"snapshotId": "SNAP-X", "status": "current", "domain": "other"}]}
                """));

        assertThat(client().currentSnapshotId("core-ip")).isEmpty();
    }

    @Test
    void noCurrentSnapshot_yieldsEmpty() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"snapshots": [{"snapshotId": "SNAP-1", "status": "building", "domain": "core-ip"}]}
                """));

        assertThat(client().currentSnapshotId("core-ip")).isEmpty();
    }

    @Test
    void transportError_yieldsEmpty_notThrow() {
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThat(client().currentSnapshotId("core-ip")).isEmpty();
    }
}
