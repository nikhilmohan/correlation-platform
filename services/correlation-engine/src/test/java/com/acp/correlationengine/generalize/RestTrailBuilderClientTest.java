package com.acp.correlationengine.generalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.observability.CorrelationMetrics;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link RestTrailBuilderClient} ({@code real} mode) tests, backed by a MockWebServer stub returning
 * Trail Builder's frozen read-API shapes from its published {@code openapi.json} — mirroring how
 * {@code RestKnowledgeClient}/{@code RestPatternManagerClient} are tested against the collaborator
 * OpenAPI.
 *
 * <ul>
 *   <li>AC42: with a base URL pointed at the stub ({@code real} routing), the same client code parses
 *       {@code GET /trails?snapshotId&domain} ({@code ListTrailsResponse.trails[].trailId}) and
 *       {@code GET /trails/{id}} ({@code TrailDetail.members[].objectType}).</li>
 *   <li>AC41: a per-trail member fetch error (5xx) is retried a bounded number of times and then
 *       yields {@link Optional#empty()} so the trail is OMITTED — while other trails still parse.</li>
 * </ul>
 */
class RestTrailBuilderClientTest {

    private MockWebServer server;

    // A minimal ListTrailsResponse (frozen: snapshotId, domain, count, trails[]{trailId,domain,memberCount}).
    private static final String LIST_BODY = """
            {
              "snapshotId": "SNAP-1",
              "domain": "core-ip",
              "count": 2,
              "trails": [
                {"trailId": "T1", "domain": "core-ip", "memberCount": 3},
                {"trailId": "T2", "domain": "core-ip", "memberCount": 2}
              ]
            }
            """;

    // A minimal TrailDetail (frozen: trailId, domain, snapshotId, members[]{managedObjectId,objectType}, memberCount).
    private static String detail(String trailId, String... typedMoIds) {
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < typedMoIds.length; i++) {
            String moId = typedMoIds[i];
            String objectType = moId.substring(0, moId.indexOf(':'));
            if (i > 0) {
                members.append(",");
            }
            members.append("{\"managedObjectId\":\"").append(moId)
                    .append("\",\"objectType\":\"").append(objectType).append("\"}");
        }
        return "{\"trailId\":\"" + trailId + "\",\"domain\":\"core-ip\",\"snapshotId\":\"SNAP-1\","
                + "\"members\":[" + members + "],\"memberCount\":" + typedMoIds.length + "}";
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RestTrailBuilderClient client(int maxRetries, CorrelationMetrics metrics) {
        RestClient http = RestClient.builder().baseUrl(server.url("/").toString()).build();
        return new RestTrailBuilderClient(http, maxRetries, metrics);
    }

    /** AC42 — real-mode: list is parsed to trailIds and the correct query params are sent. */
    @Test
    void listTrailIds_parsesFrozenListResponse_andSendsSnapshotAndDomain() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody(LIST_BODY));

        List<String> ids = client(2, CorrelationMetrics.NOOP).listTrailIds("SNAP-1", "core-ip");

        assertThat(ids).containsExactly("T1", "T2");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("/trails");
        assertThat(req.getPath()).contains("snapshotId=SNAP-1");
        assertThat(req.getPath()).contains("domain=core-ip");
    }

    /** AC42 — real-mode: member objectTypes are parsed from the frozen TrailDetail. */
    @Test
    void getTrailMemberTypes_parsesObjectTypesFromFrozenDetail() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(detail("T1", "A:node-1", "B:node-2", "C:node-3", "A:node-4")));

        Optional<Set<String>> types = client(2, CorrelationMetrics.NOOP).getTrailMemberTypes("T1");

        assertThat(types).isPresent();
        assertThat(types.get()).containsExactlyInAnyOrder("A", "B", "C"); // distinct object types
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/trails/T1");
    }

    /** AC41 — a 5xx member fetch is retried then yields empty (trail omitted), and errors are counted. */
    @Test
    void getTrailMemberTypes_fetchErrorOmitsTrail_afterBoundedRetries() {
        // maxRetries=2 -> 3 attempts total, all 500.
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        AtomicInteger fetchErrors = new AtomicInteger();
        CorrelationMetrics counting = countingErrors(fetchErrors);

        Optional<Set<String>> types = client(2, counting).getTrailMemberTypes("T_fail");

        assertThat(types).isEmpty();               // omitted from the index (AC41)
        assertThat(server.getRequestCount()).isEqualTo(3); // bounded retries: maxRetries + 1
        assertThat(fetchErrors.get()).isEqualTo(1);        // one fetch-error metric per exhausted trail
    }

    /** AC41 — one trail's fetch error omits only that trail; a subsequent good fetch still parses. */
    @Test
    void oneTrailFails_othersStillParse() {
        server.enqueue(new MockResponse().setResponseCode(500)); // maxRetries=0 -> single attempt fails
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(detail("T_ok", "A:n1", "B:n2")));

        RestTrailBuilderClient c = client(0, CorrelationMetrics.NOOP);
        assertThat(c.getTrailMemberTypes("T_fail")).isEmpty();
        assertThat(c.getTrailMemberTypes("T_ok")).contains(Set.of("A", "B"));
    }

    private static CorrelationMetrics countingErrors(AtomicInteger counter) {
        return new CorrelationMetrics() {
            @Override public void incrementAlarmsProcessed() { }
            @Override public void incrementIncidentsCreated() { }
            @Override public void incrementPatternMatch() { }
            @Override public void incrementCodebookMatch() { }
            @Override public void incrementSessionExpiration() { }
            @Override public void incrementStatusChanged(String newStatus) { }
            @Override public void incrementDlqRouted() { }
            @Override public void incrementCodebookFetchFailure() { }
            @Override public void setActiveInstances(int count) { }
            @Override public void incrementTrailBuilderFetchError() { counter.incrementAndGet(); }
            @Override public void incrementIndexRefresh(String trigger) { }
            @Override public void incrementRequiredTypesUnresolved() { }
            @Override public void setCompatibleTrailsForPattern(String patternId, int count) { }
        };
    }
}
