package com.acp.enrichment.chatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.enrichment.chatter.ChatterService.ChatterValidationException;
import com.acp.enrichment.pipeline.ChatterStep;
import com.acp.enrichment.pipeline.StepResult;
import com.acp.enrichment.ruleset.AlarmTypeVocabulary;
import com.acp.enrichment.ruleset.ChatterEntry;
import com.acp.enrichment.ruleset.RulesetConfigLoader;
import com.acp.enrichment.ruleset.RulesetRegistry;
import com.acp.eventmodel.generated.AlarmEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance criteria 18, 19, 20 — chatter list/add/remove, hot-apply live, durability. */
class ChatterServiceTest {

    private static final String YAML = """
            defaultRuleset: default
            rulesets:
              - source: default
                fieldMapping:
                  defaultObjectType: Node
                  managedObjectIdTemplate: "{objectType}:{rawObjectId}"
                  severityMap: { "1": CRITICAL }
                  alarmTypeMap: { rawField: rawAlarmType, fallback: ReachabilityLoss, onUnmapped: default, values: { "1": LinkDown } }
                  vendorRawPassthrough: ["*"]
                filterParams: { dedupWindow: 30s, selfClearHoldTime: 15s, flapN: 5, flapWindow: 60s, chatterList: [] }
              - source: nms-alpha
                fieldMapping:
                  defaultObjectType: Interface
                  managedObjectIdTemplate: "Interface:{ne}-{ifIndex}"
                  severityMap: { CRIT: CRITICAL }
                  alarmTypeMap: { rawField: rawEventType, fallback: ReachabilityLoss, onUnmapped: default, values: { LINK_DOWN: LinkDown } }
                  vendorRawPassthrough: ["ne"]
                filterParams:
                  dedupWindow: 20s
                  selfClearHoldTime: 5s
                  flapN: 3
                  flapWindow: 45s
                  chatterList:
                    - { managedObjectId: "Interface:edge1-12", eventType: communicationsAlarm }
            """;

    private RulesetRegistry registry;
    private ChatterOverlayStore overlayStore;
    private RulesetConfigLoader loader;
    private ChatterService service;
    private Path overlayFile;
    private Path rulesetsFile;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        rulesetsFile = dir.resolve("rulesets.yaml");
        Files.writeString(rulesetsFile, YAML);
        overlayFile = dir.resolve("chatter-overlay.json");
        ObjectMapper mapper = new ObjectMapper();
        registry = new RulesetRegistry();
        overlayStore = new ChatterOverlayStore(overlayFile, mapper);
        loader = new RulesetConfigLoader(rulesetsFile, overlayStore, registry,
                AlarmTypeVocabulary.coreIp(), new SimpleMeterRegistry());
        loader.loadInitial();
        service = new ChatterService(registry, overlayStore, loader, new SimpleMeterRegistry());
    }

    private AlarmEvent alarm(String moId, String eventType) {
        return new AlarmEvent().withAlarmId("a").withManagedObjectId(moId).withEventType(eventType)
                .withProbableCause("c").withAlarmType("LinkDown").withPerceivedSeverity("CRITICAL")
                .withRaisedAt("2026-06-11T10:00:00Z").withState(AlarmEvent.State.RAISED)
                .withTrailIds(new ArrayList<>());
    }

    @Test
    void getReturnsSourceChatterEntries() {
        assertThat(service.list("nms-alpha")).hasSize(1);
        assertThat(service.list("default")).isEmpty();
        assertThatThrownBy(() -> service.list("unknown"))
                .isInstanceOf(ChatterValidationException.class)
                .extracting(e -> ((ChatterValidationException) e).code()).isEqualTo("unknown_source");
    }

    @Test
    void postedEntryDroppedOnLivePathWithoutRestart() {
        ChatterStep step = new ChatterStep(new SimpleMeterRegistry());
        AlarmEvent a = alarm("Interface:edge1-77", "communicationsAlarm");

        // Before promotion: not on the list -> passes.
        StepResult before = step.apply(a, registry.forSource("nms-alpha"), com.acp.enrichment.pipeline.Path.LIVE);
        assertThat(before).isInstanceOf(StepResult.Continue.class);

        // Promote via the API write path (persist overlay + atomic registry swap).
        service.add("nms-alpha", new ChatterEntry("Interface:edge1-77", "communicationsAlarm",
                "LinkDown", "nf-observed-chatter"));

        // After promotion: the SAME running registry now drops it — no restart.
        StepResult after = step.apply(a, registry.forSource("nms-alpha"), com.acp.enrichment.pipeline.Path.LIVE);
        assertThat(after).isInstanceOf(StepResult.Drop.class);
        assertThat(service.list("nms-alpha")).anyMatch(
                e -> "Interface:edge1-77".equals(e.managedObjectId()));
    }

    @Test
    void deletedEntryEmittedAgain() {
        ChatterStep step = new ChatterStep(new SimpleMeterRegistry());
        AlarmEvent a = alarm("Interface:edge1-12", "communicationsAlarm"); // base-YAML chatter entry

        StepResult before = step.apply(a, registry.forSource("nms-alpha"), com.acp.enrichment.pipeline.Path.LIVE);
        assertThat(before).isInstanceOf(StepResult.Drop.class);

        service.remove("nms-alpha", new ChatterEntry("Interface:edge1-12", "communicationsAlarm",
                null, null));

        StepResult after = step.apply(a, registry.forSource("nms-alpha"), com.acp.enrichment.pipeline.Path.LIVE);
        assertThat(after).isInstanceOf(StepResult.Continue.class);
    }

    @Test
    void promotedEntrySurvivesReload() throws IOException {
        service.add("nms-alpha", new ChatterEntry("Interface:edge1-88", "communicationsAlarm",
                "LinkDown", "nf"));

        // Simulate a restart: a brand-new registry + loader reading base YAML + the persisted overlay.
        RulesetRegistry registry2 = new RulesetRegistry();
        ChatterOverlayStore overlay2 = new ChatterOverlayStore(overlayFile, new ObjectMapper());
        RulesetConfigLoader loader2 = new RulesetConfigLoader(rulesetsFile, overlay2, registry2,
                AlarmTypeVocabulary.coreIp(), new SimpleMeterRegistry());
        loader2.loadInitial();

        assertThat(registry2.forSource("nms-alpha").filterParams().chatterList())
                .anyMatch(e -> "Interface:edge1-88".equals(e.managedObjectId()));
    }

    @Test
    void malformedEntryRejectedAndNoChange() {
        int before = service.list("nms-alpha").size();
        assertThatThrownBy(() -> service.add("nms-alpha",
                new ChatterEntry(null, "communicationsAlarm", null, null)))
                .isInstanceOf(ChatterValidationException.class)
                .extracting(e -> ((ChatterValidationException) e).code()).isEqualTo("malformed_entry");
        assertThat(service.list("nms-alpha")).hasSize(before);
    }

    @Test
    void duplicateAddRejected() {
        assertThatThrownBy(() -> service.add("nms-alpha",
                new ChatterEntry("Interface:edge1-12", "communicationsAlarm", null, null)))
                .isInstanceOf(ChatterValidationException.class)
                .extracting(e -> ((ChatterValidationException) e).code()).isEqualTo("duplicate_entry");
    }
}
