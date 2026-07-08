package com.acp.correlationengine.config;

import com.acp.correlationengine.api.CorrelationResetService;
import com.acp.correlationengine.api.StatsAggregator;
import com.acp.correlationengine.api.StatsAggregator.RcaAccuracyOracle;
import com.acp.correlationengine.codebook.CodebookDecoder;
import com.acp.correlationengine.codebook.CodebookGeneratorClient;
import com.acp.correlationengine.codebook.CodebookRefreshService;
import com.acp.correlationengine.codebook.CodebookStore;
import com.acp.correlationengine.codebook.RestCodebookGeneratorClient;
import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import com.acp.correlationengine.correlate.ConflictResolver;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import com.acp.correlationengine.generalize.CompatibilityEvaluator;
import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.correlationengine.generalize.RequiredObjectTypesResolver;
import com.acp.correlationengine.generalize.RestTrailBuilderClient;
import com.acp.correlationengine.generalize.TrailBuilderClient;
import com.acp.correlationengine.incident.IncidentFactory;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.knowledge.KnowledgeClient;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.knowledge.RestKnowledgeClient;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.observability.MicrometerCorrelationMetrics;
import com.acp.correlationengine.observability.ReadinessHealthIndicator;
import com.acp.correlationengine.pattern.PatternManagerClient;
import com.acp.correlationengine.pattern.PatternRefreshService;
import com.acp.correlationengine.pattern.PatternStore;
import com.acp.correlationengine.pattern.RestPatternManagerClient;
import com.acp.correlationengine.generalize.StartupSnapshotDiscovery;
import com.acp.correlationengine.topology.RestTopologyClient;
import com.acp.correlationengine.topology.TopologyClient;
import com.acp.eventmodel.EventCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the correlation core, the outbound clients (config-switchable mock/real by base URL — same
 * code path, only the URL differs), the Knowledge params provider, the pattern/codebook stores +
 * refresh services, and observability. Kafka producers/consumers + the expiry scheduler are wired in
 * {@link KafkaConfig} (guarded so unit slice tests without Kafka still load the core).
 */
@Configuration
public class CorrelationEngineConfig {

    @Bean
    public EventCodec eventCodec() {
        return new EventCodec();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CorrelationMetrics correlationMetrics(MeterRegistry registry) {
        return new MicrometerCorrelationMetrics(registry);
    }

    // --- Outbound clients (mock/real by base URL) --------------------------------------------

    @Bean
    public PatternManagerClient patternManagerClient(RestClient.Builder builder,
            CorrelationEngineProperties props) {
        return new RestPatternManagerClient(builder.baseUrl(props.patternManagerBaseUrl()).build());
    }

    @Bean
    public CodebookGeneratorClient codebookGeneratorClient(RestClient.Builder builder,
            CorrelationEngineProperties props) {
        return new RestCodebookGeneratorClient(
                builder.baseUrl(props.codebookGeneratorBaseUrl()).build());
    }

    @Bean
    public KnowledgeClient knowledgeClient(RestClient.Builder builder,
            CorrelationEngineProperties props) {
        return new RestKnowledgeClient(builder.baseUrl(props.knowledgeBaseUrl()).build(),
                props.knowledgeDomain());
    }

    @Bean
    public KnowledgeParamsProvider knowledgeParamsProvider(KnowledgeClient client,
            CorrelationEngineProperties props) {
        return new KnowledgeParamsProvider(client, props.knowledgeRefreshMs());
    }

    @Bean
    public TrailBuilderClient trailBuilderClient(RestClient.Builder builder,
            CorrelationEngineProperties props, CorrelationMetrics metrics) {
        // Config-switchable mock/real by base URL only — same code path (AC42). In mock mode the
        // base URL points at a WireMock/MockWebServer stub generated from Trail Builder's openapi.json.
        return new RestTrailBuilderClient(
                builder.baseUrl(props.trailBuilderBaseUrl()).build(),
                props.trailBuilderMaxRetries(), metrics);
    }

    @Bean
    public TopologyClient topologyClient(RestClient.Builder builder,
            CorrelationEngineProperties props) {
        // Config-switchable mock/real by base URL only — same code path. In mock mode the base URL
        // points at a stub generated from Topology's published openapi.json. Used solely for the
        // startup GET /topology/snapshots current-snapshot discovery (an existing Topology read).
        return new RestTopologyClient(builder.baseUrl(props.topologyBaseUrl()).build());
    }

    // --- Pattern generalization: compatibility index ------------------------------------------

    @Bean
    public CompatibilityEvaluator compatibilityEvaluator() {
        return new CompatibilityEvaluator();
    }

    @Bean
    public RequiredObjectTypesResolver requiredObjectTypesResolver(TrailBuilderClient trailBuilder) {
        return new RequiredObjectTypesResolver(trailBuilder);
    }

    @Bean
    public CompatibilityIndexService compatibilityIndexService(PatternStore patternStore,
            TrailBuilderClient trailBuilder, RequiredObjectTypesResolver resolver,
            CompatibilityEvaluator evaluator, CorrelationMetrics metrics,
            CorrelationEngineProperties props) {
        return new CompatibilityIndexService(patternStore, trailBuilder, resolver, evaluator,
                metrics, props.knowledgeDomain());
    }

    @Bean
    public StartupSnapshotDiscovery startupSnapshotDiscovery(TopologyClient topologyClient,
            PatternManagerClient patternManagerClient, CompatibilityIndexService compatibilityIndex,
            CorrelationEngineProperties props) {
        return new StartupSnapshotDiscovery(topologyClient, patternManagerClient, compatibilityIndex,
                props.knowledgeDomain());
    }

    // --- Reference stores + refresh services -------------------------------------------------

    @Bean
    public PatternStore patternStore() {
        return new PatternStore();
    }

    @Bean
    public CodebookStore codebookStore() {
        return new CodebookStore();
    }

    @Bean
    public PatternRefreshService patternRefreshService(PatternManagerClient client,
            PatternStore store) {
        return new PatternRefreshService(client, store);
    }

    @Bean
    public CodebookRefreshService codebookRefreshService(CodebookGeneratorClient client,
            CodebookStore store, CorrelationMetrics metrics) {
        return new CodebookRefreshService(client, store, metrics);
    }

    // --- Correlation core --------------------------------------------------------------------

    @Bean
    public CodebookDecoder codebookDecoder() {
        return new CodebookDecoder();
    }

    @Bean
    public ConflictResolver conflictResolver() {
        return new ConflictResolver();
    }

    @Bean
    public IncidentFactory incidentFactory(Clock clock) {
        return new IncidentFactory(clock);
    }

    @Bean
    public CorrelationEngine correlationEngine(
            CompatibilityIndexService compatibilityIndex,
            CodebookStore codebookStore,
            CodebookDecoder codebookDecoder,
            ConflictResolver conflictResolver,
            IncidentFactory incidentFactory,
            IncidentRepository incidentRepository,
            KnowledgeParamsProvider knowledgeParams,
            CorrelationResultEmitter resultEmitter,
            AlarmStatusEmitter statusEmitter,
            CorrelationMetrics metrics) {
        return new CorrelationEngine(compatibilityIndex, codebookStore, codebookDecoder,
                conflictResolver, incidentFactory, incidentRepository, knowledgeParams, resultEmitter,
                statusEmitter, metrics);
    }

    // --- Read API ----------------------------------------------------------------------------

    @Bean
    public RcaAccuracyOracle rcaAccuracyOracle() {
        // Eval-mode oracle wiring is out of scope for the engine at production runtime (D2):
        // rcaAccuracy stays null unless a labels oracle bean is provided.
        return RcaAccuracyOracle.DISABLED;
    }

    @Bean
    public StatsAggregator statsAggregator(IncidentRepository repository, CorrelationEngine engine,
            RcaAccuracyOracle rcaOracle) {
        return new StatsAggregator(repository, engine, rcaOracle);
    }

    @Bean
    public CorrelationResetService correlationResetService(IncidentRepository repository,
            CorrelationEngine engine, CorrelationMetrics metrics) {
        return new CorrelationResetService(repository, engine, metrics);
    }

    @Bean
    public HealthIndicator correlationReadiness(KnowledgeParamsProvider knowledgeParams,
            CompatibilityIndexService compatibilityIndex) {
        return new ReadinessHealthIndicator(knowledgeParams, compatibilityIndex);
    }
}
