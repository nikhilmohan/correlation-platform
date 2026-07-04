package com.acp.correlationengine.support;

import com.acp.correlationengine.codebook.CodebookDecoder;
import com.acp.correlationengine.codebook.CodebookStore;
import com.acp.correlationengine.correlate.ConflictResolver;
import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.generalize.CompatibilityEvaluator;
import com.acp.correlationengine.generalize.CompatibilityIndexService;
import com.acp.correlationengine.generalize.RequiredObjectTypesResolver;
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
 *
 * <p>Under pattern generalization the engine's fan-out driver is the {@link CompatibilityIndexService}
 * (not the discovery-trail registry). The harness backs it with a {@link FakeTrailBuilderClient} so a
 * test can declare each trail's member object types, seed a snapshot, and rebuild the index — exactly
 * as production does from the real Trail Builder API. {@link #addPatternCompatibleWith} is a
 * convenience that declares a pattern and the trails it should be compatible with (deriving the
 * trail/member types from the pattern's own sample-alarm object types), then rebuilds.
 */
public final class EngineHarness {

    public final PatternStore patternStore = new PatternStore();
    public final CodebookStore codebookStore = new CodebookStore();
    public final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
    public final RecordingResultEmitter results = new RecordingResultEmitter();
    public final RecordingStatusEmitter statuses = new RecordingStatusEmitter();
    public final KnowledgeParamsProvider knowledge;
    public final FakeTrailBuilderClient trailBuilder = new FakeTrailBuilderClient();
    public final CompatibilityIndexService compatibilityIndex;
    public final CorrelationEngine engine;

    private static final String SNAPSHOT = "SNAP-TEST";

    public EngineHarness(MatchParams params) {
        this.knowledge = FixedKnowledgeParams.provider(params);
        Clock clock = Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneOffset.UTC);
        this.compatibilityIndex = new CompatibilityIndexService(
                patternStore,
                trailBuilder,
                new RequiredObjectTypesResolver(trailBuilder),
                new CompatibilityEvaluator(),
                CorrelationMetrics.NOOP,
                "core-ip");
        this.engine = new CorrelationEngine(
                compatibilityIndex,
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

    /**
     * Register a pattern and rebuild the compatibility index. The pattern becomes compatible with any
     * trail already declared on the fake Trail Builder whose member object types host the pattern's
     * required types. Callers that rely on discovery-trail-only behaviour should first declare the
     * discovery trail's members via {@link #declareTrail}.
     */
    public void addPattern(PatternRef pattern) {
        // Backward compatibility (AC33): the discovery trail always hosts its own pattern. Auto-declare
        // it with member object types that UNION the pattern's required object types with whatever the
        // trail already hosts, so multiple patterns on one discovery trail all remain hostable — unless
        // the test explicitly declared the trail with fewer types (e.g. AC36's "make it incompatible").
        String discovery = pattern.discoveryTrailId();
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(
                trailBuilder.getTrailMemberTypes(discovery).orElse(java.util.Set.of()));
        if (!trailBuilder.isExplicitlyDeclared(discovery)) {
            merged.addAll(pattern.sampleAlarmObjectTypes().values());
            trailBuilder.autoDeclareTrail(discovery, List.copyOf(merged));
        }
        patternStore.upsert(pattern);
        rebuild();
    }

    /**
     * Convenience: register {@code pattern} and declare {@code trailIds} as trails whose members host
     * the pattern (member object types = the pattern's required object types), then rebuild. Every
     * listed trail becomes compatible with the pattern.
     */
    public void addPatternCompatibleWith(PatternRef pattern, String... trailIds) {
        List<String> required = List.copyOf(pattern.sampleAlarmObjectTypes().values());
        for (String trailId : trailIds) {
            trailBuilder.declareTrail(trailId, required);
        }
        patternStore.upsert(pattern);
        rebuild();
    }

    /** Declare a trail's member object types on the fake Trail Builder (no rebuild). */
    public void declareTrail(String trailId, List<String> memberObjectTypes) {
        trailBuilder.declareTrail(trailId, memberObjectTypes);
    }

    /** Rebuild the compatibility index against the currently declared trails + approved patterns. */
    public void rebuild() {
        compatibilityIndex.rebuildAll(SNAPSHOT, "core-ip");
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
