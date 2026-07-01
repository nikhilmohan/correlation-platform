package com.acp.enrichment.config;

import com.acp.enrichment.chatter.ChatterOverlayStore;
import com.acp.enrichment.chatter.ChatterService;
import com.acp.enrichment.ruleset.AlarmTypeVocabulary;
import com.acp.enrichment.ruleset.RulesetConfigLoader;
// AlarmTypeVocabulary bean now provided by KnowledgeConfig (fetched from Knowledge).
import com.acp.enrichment.ruleset.RulesetRegistry;
import com.acp.eventmodel.EventCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core bean wiring: the event codec, the ruleset registry/loader/overlay/vocabulary, the chatter
 * service, and a system {@link Clock}. The {@link RulesetConfigLoader#loadInitial()} call is driven
 * by {@link com.acp.enrichment.observability.StartupRunner} so a bad config fails readiness rather
 * than crashing wiring.
 */
@Configuration
@EnableConfigurationProperties(EnrichmentProperties.class)
public class CoreConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EventCodec eventCodec() {
        return new EventCodec();
    }

    /** The Jackson mapper used for envelope parsing + overlay persistence (canonical wire config). */
    @Bean
    public ObjectMapper enrichmentObjectMapper(EventCodec codec) {
        return codec.objectMapper();
    }

    @Bean
    public RulesetRegistry rulesetRegistry() {
        return new RulesetRegistry();
    }

    @Bean
    public ChatterOverlayStore chatterOverlayStore(EnrichmentProperties props,
            ObjectMapper mapper) {
        return new ChatterOverlayStore(Path.of(props.getChatterOverlayFile()), mapper);
    }

    @Bean
    public RulesetConfigLoader rulesetConfigLoader(EnrichmentProperties props,
            ChatterOverlayStore overlayStore, RulesetRegistry registry,
            AlarmTypeVocabulary vocabulary, MeterRegistry meters) {
        return new RulesetConfigLoader(Path.of(props.getRulesetsFile()), overlayStore, registry,
                vocabulary, meters);
    }

    @Bean
    public ChatterService chatterService(RulesetRegistry registry, ChatterOverlayStore overlayStore,
            RulesetConfigLoader loader, MeterRegistry meters) {
        return new ChatterService(registry, overlayStore, loader, meters);
    }
}
