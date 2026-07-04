package com.acp.enrichment.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.enrichment.trail.TrailBuilderClient;
import com.acp.enrichment.trail.TrailLookupException;
import com.acp.eventmodel.generated.AlarmEvent;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Acceptance criterion 6 — every survivor carries the trailIds returned by Trail Builder. */
class TrailTagStepTest {

    private WireMockServer wireMock;
    private TrailTagStep step;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        TrailBuilderClient client = new TrailBuilderClient(
                RestClient.builder().baseUrl(wireMock.baseUrl()).build());
        step = new TrailTagStep(client, "core-ip", new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private AlarmEvent alarm() {
        return new AlarmEvent().withAlarmId("a").withManagedObjectId("Interface:edge1-12")
                .withEventType("communicationsAlarm").withProbableCause("linkDown")
                .withAlarmType("LinkDown").withPerceivedSeverity("CRITICAL")
                .withRaisedAt("2026-06-11T10:00:00Z").withState(AlarmEvent.State.RAISED)
                .withTrailIds(new ArrayList<>());
    }

    @Test
    void setsTrailIdsFromTrailBuilder() {
        wireMock.stubFor(get(urlPathEqualTo("/trails/by-object"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"managedObjectId\":\"Interface:edge1-12\","
                                + "\"domain\":\"core-ip\",\"trailIds\":[\"trail-7a3f\",\"trail-9\"]}")));
        AlarmEvent tagged = step.tag(alarm());
        assertThat(tagged.getTrailIds()).containsExactly("trail-7a3f", "trail-9");
    }

    @Test
    void setsEmptyArrayWhenTrailBuilderReturnsNone() {
        wireMock.stubFor(get(urlPathEqualTo("/trails/by-object"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"managedObjectId\":\"Interface:edge1-12\","
                                + "\"domain\":\"core-ip\",\"trailIds\":[]}")));
        AlarmEvent tagged = step.tag(alarm());
        assertThat(tagged.getTrailIds()).isNotNull().isEmpty();
    }

    @Test
    void lookupFailureRaisesTrailLookupExceptionForDlqRouting() {
        wireMock.stubFor(get(urlPathEqualTo("/trails/by-object"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> step.tag(alarm())).isInstanceOf(TrailLookupException.class);
    }
}
