package com.acp.correlationengine.support;

import com.acp.correlationengine.codebook.CodebookDecoder;
import com.acp.correlationengine.codebook.CodebookStore;
import com.acp.correlationengine.correlate.ConflictResolver;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.incident.InMemoryIncidentRepository;
import com.acp.correlationengine.incident.IncidentFactory;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.TrailScenarioSignature;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.pattern.PatternStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Assembles a {@link CorrelationEngine} over pure in-memory / recording collaborators for fast,
 * deterministic unit tests — the Kafka-free path the design calls out. Exposes the recording
 * emitters + the incident repository so every acceptance criterion can be asserted directly.
 */
public final class EngineHarness {

    public final PatternStore patternStore = new PatternStore();
    public final CodebookStore codebookStore = new CodebookStore();
    public final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
    public final RecordingResultEmitter results = new RecordingResultEmitter();
    public final RecordingStatusEmitter statuses = new RecordingStatusEmitter();
    public final KnowledgeParamsProvider knowledge;
    public final CorrelationEngine engine;

    public EngineHarness(MatchParams params) {
        this.knowledge = FixedKnowledgeParams.provider(params);
        Clock clock = Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneOffset.UTC);
        this.engine = new CorrelationEngine(
                patternStore,
                codebookStore,
                new CodebookDecoder(),
                new ConflictResolver(),
                new IncidentFactory(clock),
                incidents,
                knowledge,
                results,
                statuses,
                CorrelationMetrics.NOOP);
    }

    public EngineHarness() {
        this(FixedKnowledgeParams.defaults());
    }

    public void addPattern(PatternRef pattern) {
        patternStore.upsert(pattern);
    }

    public void addSignatures(String snapshotId, String trailId, List<TrailScenarioSignature> sigs) {
        codebookStore.replaceScope(snapshotId, trailId, sigs);
    }

    public void feed(ObservedAlarm alarm, List<String> trailIds, long nowEpochMs) {
        engine.onAlarm(alarm, trailIds, nowEpochMs);
    }

    public void feed(ObservedAlarm alarm, String trailId, long nowEpochMs) {
        engine.onAlarm(alarm, List.of(trailId), nowEpochMs);
    }

    public void tick(long nowEpochMs) {
        engine.onClockTick(nowEpochMs);
    }
}
