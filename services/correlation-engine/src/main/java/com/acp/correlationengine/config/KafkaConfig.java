package com.acp.correlationengine.config;

import com.acp.correlationengine.codebook.CodebookRefreshService;
import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.correlationengine.integration.AlarmIngestConsumer;
import com.acp.correlationengine.integration.CodebookConsumer;
import com.acp.correlationengine.integration.DlqProducer;
import com.acp.correlationengine.integration.ExpiryScheduler;
import com.acp.correlationengine.integration.KafkaAlarmStatusEmitter;
import com.acp.correlationengine.integration.KafkaCorrelationResultEmitter;
import com.acp.correlationengine.integration.PatternApprovedConsumer;
import com.acp.correlationengine.integration.ProcessedEventStore;
import com.acp.correlationengine.integration.StartupBootstrapRunner;
import com.acp.correlationengine.integration.TrailsBuiltConsumer;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.pattern.PatternRefreshService;
import com.acp.eventmodel.EventCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka wiring — the produced {@code CorrelationResultEmitter} / {@code AlarmStatusEmitter}
 * (idempotent producers), the DLQ producer, the three consumers ({@code alarms.persisted.live},
 * {@code patterns.approved}, {@code codebook.generated}), the wall-clock expiry scheduler, and the
 * startup bootstrap runner. Guarded by {@code correlation-engine.kafka.enabled} (default true) so
 * MVC slice tests can load the read API without a broker.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "correlation-engine.kafka.enabled", havingValue = "true",
        matchIfMissing = true)
public class KafkaConfig {

    @Bean
    public DlqProducer dlqProducer(KafkaTemplate<String, String> kafka, CorrelationMetrics metrics) {
        return new DlqProducer(kafka, metrics);
    }

    @Bean
    public CorrelationResultEmitter correlationResultEmitter(KafkaTemplate<String, String> kafka,
            EventCodec codec, CorrelationEngineProperties props) {
        return new KafkaCorrelationResultEmitter(kafka, codec, props);
    }

    @Bean
    public AlarmStatusEmitter alarmStatusEmitter(KafkaTemplate<String, String> kafka,
            EventCodec codec, CorrelationEngineProperties props) {
        return new KafkaAlarmStatusEmitter(kafka, codec, props);
    }

    @Bean
    public AlarmIngestConsumer alarmIngestConsumer(CorrelationEngine engine, EventCodec codec,
            DlqProducer dlq, CorrelationEngineProperties props) {
        return new AlarmIngestConsumer(engine, codec, dlq, props);
    }

    @Bean
    public PatternApprovedConsumer patternApprovedConsumer(PatternRefreshService refreshService,
            CompatibilityIndexService indexService, ProcessedEventStore processedEvents,
            EventCodec codec, DlqProducer dlq, CorrelationEngineProperties props) {
        return new PatternApprovedConsumer(refreshService, indexService, processedEvents, codec, dlq,
                props);
    }

    @Bean
    public TrailsBuiltConsumer trailsBuiltConsumer(CompatibilityIndexService indexService,
            ProcessedEventStore processedEvents, EventCodec codec, DlqProducer dlq) {
        return new TrailsBuiltConsumer(indexService, processedEvents, codec, dlq);
    }

    @Bean
    public CodebookConsumer codebookConsumer(CodebookRefreshService refreshService,
            ProcessedEventStore processedEvents, EventCodec codec, DlqProducer dlq,
            CorrelationEngineProperties props) {
        return new CodebookConsumer(refreshService, processedEvents, codec, dlq, props);
    }

    @Bean
    public ExpiryScheduler expiryScheduler(CorrelationEngine engine) {
        return new ExpiryScheduler(engine);
    }

    @Bean
    public StartupBootstrapRunner startupBootstrapRunner(PatternRefreshService patternRefresh,
            KnowledgeParamsProvider knowledgeParams, CompatibilityIndexService indexService) {
        return new StartupBootstrapRunner(patternRefresh, knowledgeParams, indexService);
    }
}
