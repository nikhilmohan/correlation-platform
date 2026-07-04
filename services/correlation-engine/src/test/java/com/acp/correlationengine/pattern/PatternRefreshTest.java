package com.acp.correlationengine.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
import com.acp.correlationengine.support.EngineHarness;
import com.acp.correlationengine.support.Fixtures;
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

    /**
     * A PatternView body in the REAL Pattern Manager shape: {@code sequence} is an array of
     * {@code SequenceElementView} OBJECTS ({@code {"alarmType": ..., "optional": ...}}), matching the
     * Pattern Manager {@code openapi.json} {@code #/components/schemas/SequenceElementView}. The
     * pre-fix mock served bare strings ({@code ["LOS", ...]}), which hid the parse bug.
     */
    private static String patternPageBody() {
        return """
                {
                  "items": [
                    {
                      "patternId": "P",
                      "trailId": "T",
                      "sequence": [
                        {"alarmType": "IPLinkDown", "optional": false},
                        {"alarmType": "LinkBundleDegraded", "optional": false}
                      ],
                      "rootCauseAlarmType": "IPLinkDown",
                      "confidence": 0.87,
                      "sessionWindow": {"windowMs": 45000, "type": "gap-based"},
                      "sampleAlarms": [
                        {"alarmType": "IPLinkDown", "managedObjectId": "IpLink:link-1"},
                        {"alarmType": "LinkBundleDegraded", "managedObjectId": "Bundle:bundle-1"}
                      ]
                    }
                  ],
                  "total": 1, "limit": 50, "offset": 0
                }
                """;
    }

    @Test
    void ac27_trailPlacementComesFromReadApiNotEvent() throws InterruptedException {
        // The read API's PatternView carries trailId = "T" (the event would not).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(patternPageBody()));

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

    /**
     * Regression for the sequence-parse bug: the object-shaped {@code sequence} must be mapped to the
     * list of alarmType tokens IN ORDER, and {@code openingAlarmType()} must be the first element's
     * alarmType. Pre-fix, {@code n.asText()} on an OBJECT node yielded "" so the opening type was ""
     * and nothing ever matched (0 auto-correlation).
     */
    @Test
    void objectShapedSequenceMapsToAlarmTypeTokensAndOpeningType() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(patternPageBody()));

        PatternStore store = new PatternStore();
        new PatternRefreshService(client, store).refreshOnApproval();

        PatternRef p = store.activePatternsOn("T").get(0);
        assertThat(p.sequence()).containsExactly("IPLinkDown", "LinkBundleDegraded");
        assertThat(p.openingAlarmType()).isEqualTo("IPLinkDown");
    }

    /**
     * End-to-end regression proving the behavior that was silently broken: a pattern loaded from the
     * REAL object-shaped {@code sequence} actually OPENS a correlation instance when an alarm of the
     * opening alarmType arrives on its trail, and fires a correlated incident on full match. Pre-fix,
     * the opening type was "" so no instance was ever opened -> 0 incidents.
     */
    @Test
    void objectShapedSequencePatternOpensInstanceAndFiresIncident() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(patternPageBody()));

        EngineHarness harness = new EngineHarness();
        new PatternRefreshService(client, harness.patternStore).refreshOnApproval();
        // Under pattern generalization the fan-out driver is the compatibility index: declare the
        // discovery trail's members (which host the pattern's IpLink + Bundle object types) and build
        // the index so the pattern is a candidate on trail "T".
        harness.declareTrail("T", List.of("IpLink", "Bundle"));
        harness.rebuild();

        long t0 = Fixtures.T0;
        // opening alarm on trail T -> lazily opens an instance for pattern P
        harness.feed(Fixtures.alarm("a1", "IPLinkDown", t0), "T", t0);
        assertThat(harness.engine.hasInstance("T", "P")).isTrue();

        // second sequence element completes the full match -> fire-and-destroy + incident
        harness.feed(Fixtures.alarm("a2", "LinkBundleDegraded", t0 + 1000), "T", t0 + 1000);
        assertThat(harness.engine.hasInstance("T", "P")).isFalse();
        assertThat(harness.incidents.totalIncidents()).isEqualTo(1);
        assertThat(harness.results.emitted).hasSize(1);
    }
}
