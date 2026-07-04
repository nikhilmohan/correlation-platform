package com.acp.patternmanager.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.enrichment.PatternEnrichmentService.MinedPatternView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parse of the optional frozen {@code PatternMinedEvent.sampleAlarms[]} off the raw payload node
 * (design DA-4), and its threading into {@link MinedPatternView} (spec-sample-alarms AC-SA-5a/5b +
 * the non-fatal malformed-sample path).
 */
class SampleAlarmParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode payload(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static final String WITH_SAMPLE = """
            {
              "sequence": ["FiberFault", "LinkDown", "PortDown"],
              "support": 0.4, "confidence": 0.9, "lift": 3.0, "trailId": "trail:1",
              "timing": { "timeframeMs": 9000 },
              "provenance": { "sourceWindowId": "sw:1", "snapshotId": "snap-9",
                              "codebookVersion": "cb-3", "anchorScenarioId": "scenario-42" },
              "sampleAlarms": [
                { "alarmId": "alm-1001", "alarmType": "FiberFault", "raisedAt": "2026-06-20T14:03:11Z",
                  "managedObjectId": "OpticalPort:lon-agg-1/xe-0/0/3", "perceivedSeverity": "critical" },
                { "alarmId": "alm-1002", "alarmType": "LinkDown", "raisedAt": "2026-06-20T14:03:12Z",
                  "managedObjectId": "Interface:lon-agg-1/ge-0/0/1", "perceivedSeverity": "major" }
              ]
            }
            """;

    // AC-SA-5a: sampleAlarms[] present -> parsed into MinedPatternView in received order, 5 fields.
    @Test
    void parsesSampleAlarmsIntoViewInOrder() throws Exception {
        MinedPatternView view = MinedPatternView.from(payload(WITH_SAMPLE), mapper);

        assertThat(view.sampleAlarms()).hasSize(2);
        SampleAlarm first = view.sampleAlarms().get(0);
        assertThat(first.alarmId()).isEqualTo("alm-1001");
        assertThat(first.alarmType()).isEqualTo("FiberFault");
        assertThat(first.raisedAt().toString()).isEqualTo("2026-06-20T14:03:11Z");
        assertThat(first.managedObjectId()).isEqualTo("OpticalPort:lon-agg-1/xe-0/0/3");
        assertThat(first.perceivedSeverity()).isEqualTo("critical");
        assertThat(view.sampleAlarms().get(1).alarmId()).isEqualTo("alm-1002");
    }

    // AC-SA-5b backward-compat: no sampleAlarms field -> empty list (pattern still parses fully).
    @Test
    void absentSampleAlarmsFieldYieldsEmptyList() throws Exception {
        String noSample = WITH_SAMPLE.replaceAll("(?s),\\s*\"sampleAlarms\".*?\\]\\s*(?=})", "");
        JsonNode node = payload(noSample);
        assertThat(node.has("sampleAlarms")).isFalse();

        MinedPatternView view = MinedPatternView.from(node, mapper);

        assertThat(view.sampleAlarms()).isNotNull();
        assertThat(view.sampleAlarms()).isEmpty();
        assertThat(view.sequence()).containsExactly("FiberFault", "LinkDown", "PortDown");
    }

    // Non-fatal malformed sample: a bad entry (missing required field / bad timestamp) is dropped
    // best-effort; the valid entries still parse; the pattern is unaffected (design Error handling).
    @Test
    void malformedSampleEntriesAreDroppedNotFatal() throws Exception {
        String malformed = """
                {
                  "sequence": ["FiberFault"],
                  "support": 0.4, "confidence": 0.9, "lift": 3.0, "trailId": "trail:1",
                  "timing": { "timeframeMs": 9000 },
                  "provenance": { "sourceWindowId": "sw:1", "snapshotId": "snap-9", "codebookVersion": "cb-3" },
                  "sampleAlarms": [
                    { "alarmId": "alm-ok", "alarmType": "FiberFault", "raisedAt": "2026-06-20T14:03:11Z",
                      "managedObjectId": "OpticalPort:o1", "perceivedSeverity": "critical" },
                    { "alarmId": "alm-no-sev", "alarmType": "FiberFault", "raisedAt": "2026-06-20T14:03:12Z",
                      "managedObjectId": "OpticalPort:o2" },
                    { "alarmId": "alm-bad-ts", "alarmType": "FiberFault", "raisedAt": "not-a-timestamp",
                      "managedObjectId": "OpticalPort:o3", "perceivedSeverity": "major" }
                  ]
                }
                """;

        List<SampleAlarm> parsed = SampleAlarm.parse(payload(malformed));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).alarmId()).isEqualTo("alm-ok");
    }

    // not-an-array sampleAlarms -> empty (defensive backward-compat).
    @Test
    void nonArraySampleAlarmsYieldsEmpty() throws Exception {
        List<SampleAlarm> parsed = SampleAlarm.parse(payload("{ \"sampleAlarms\": \"oops\" }"));
        assertThat(parsed).isEmpty();
    }
}
