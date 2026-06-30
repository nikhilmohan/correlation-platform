package com.acp.enrichment.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.enrichment.chatter.ChatterOverlayStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ruleset loading + validation (default present, vocab tokens, sane params). */
class RulesetConfigLoaderTest {

    private static final String VALID = """
            defaultRuleset: default
            rulesets:
              - source: default
                fieldMapping:
                  defaultObjectType: Node
                  managedObjectIdTemplate: "{objectType}:{rawObjectId}"
                  severityMap: { "1": CRITICAL }
                  alarmTypeMap:
                    rawField: rawAlarmType
                    fallback: ReachabilityLoss
                    onUnmapped: default
                    values: { "1": LinkDown }
                  vendorRawPassthrough: ["*"]
                filterParams:
                  dedupWindow: 30s
                  selfClearHoldTime: 15s
                  flapN: 5
                  flapWindow: 60s
                  chatterList: []
              - source: nms-alpha
                fieldMapping:
                  defaultObjectType: Interface
                  managedObjectIdTemplate: "Interface:{ne}-{ifIndex}"
                  severityMap: { CRIT: CRITICAL }
                  alarmTypeMap:
                    rawField: rawEventType
                    fallback: ReachabilityLoss
                    onUnmapped: default
                    values: { LINK_DOWN: LinkDown }
                  vendorRawPassthrough: ["ne"]
                filterParams:
                  dedupWindow: 20s
                  selfClearHoldTime: 5s
                  flapN: 3
                  flapWindow: 45s
                  chatterList:
                    - { managedObjectId: "Interface:edge1-12", eventType: communicationsAlarm }
            """;

    private RulesetConfigLoader loader(Path rulesets, Path overlay) {
        ObjectMapper mapper = new ObjectMapper();
        ChatterOverlayStore overlayStore = new ChatterOverlayStore(overlay, mapper);
        return new RulesetConfigLoader(rulesets, overlayStore, new RulesetRegistry(),
                AlarmTypeVocabulary.coreIp(), new SimpleMeterRegistry());
    }

    @Test
    void loadsValidRulesetsWithDefault(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rulesets.yaml");
        Files.writeString(file, VALID);
        RulesetRegistry registry = new RulesetRegistry();
        RulesetConfigLoader loader = new RulesetConfigLoader(file,
                new ChatterOverlayStore(dir.resolve("overlay.json"), new ObjectMapper()), registry,
                AlarmTypeVocabulary.coreIp(), new SimpleMeterRegistry());
        loader.loadInitial();

        assertThat(registry.isLoaded()).isTrue();
        assertThat(registry.hasSource("nms-alpha")).isTrue();
        assertThat(registry.getDefault().source()).isEqualTo("default");
        assertThat(registry.forSource("nms-alpha").filterParams().chatterList()).hasSize(1);
    }

    @Test
    void failsWhenDefaultMissing(@TempDir Path dir) throws IOException {
        String noDefault = VALID.replace("source: default", "source: only-alpha")
                .replace("defaultRuleset: default", "defaultRuleset: only-alpha");
        Path file = dir.resolve("rulesets.yaml");
        Files.writeString(file, noDefault);
        RulesetConfigLoader loader = loader(file, dir.resolve("overlay.json"));
        assertThatThrownBy(loader::loadInitial).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonVocabAlarmTypeValue(@TempDir Path dir) throws IOException {
        String bad = VALID.replace("values: { \"1\": LinkDown }", "values: { \"1\": NotAToken }");
        Path file = dir.resolve("rulesets.yaml");
        Files.writeString(file, bad);
        RulesetConfigLoader loader = loader(file, dir.resolve("overlay.json"));
        assertThatThrownBy(loader::loadInitial).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alarmTypeVocabulary");
    }

    @Test
    void failsWhenFileMissing(@TempDir Path dir) {
        RulesetConfigLoader loader = loader(dir.resolve("missing.yaml"), dir.resolve("o.json"));
        assertThatThrownBy(loader::loadInitial).isInstanceOf(IllegalStateException.class);
    }
}
