package com.acp.correlationengine.correlate;

import com.acp.correlationengine.codebook.CodebookDecoder;
import com.acp.correlationengine.codebook.CodebookStore;
import com.acp.correlationengine.incident.IncidentFactory;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.model.CorrelationInstance;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.TrailScenarioSignature;
import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.pattern.PatternStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The correlation core — the Kafka-free heart of the service, driving the
 * {@code (trailId, patternId)} correlation-instance lifecycle (spec Task 4):
 * lazy-init, incremental match, fire-and-destroy on full match, session-expiry destroy + revert.
 *
 * <p>It is deliberately independent of Kafka Streams so every acceptance criterion maps to a fast,
 * deterministic JUnit test: alarms are fed via {@link #onAlarm}, wall-clock time is advanced
 * explicitly via {@link #onClockTick}, and outputs (incidents, status changes) go through injected
 * ports. In production the Kafka Streams {@code CorrelationInstanceProcessor} + {@code ExpiryPunctuator}
 * delegate to this same core.
 *
 * <p>Isolation (AC2/AC8) is structural: instances are keyed per {@code (trailId, patternId)} in a
 * private registry; no shared mutable buffer bleeds across instances.
 */
public class CorrelationEngine {

    private final PatternStore patternStore;
    private final CodebookStore codebookStore;
    private final CodebookDecoder codebookDecoder;
    private final ConflictResolver conflictResolver;
    private final IncidentFactory incidentFactory;
    private final IncidentRepository incidentRepository;
    private final KnowledgeParamsProvider knowledgeParams;
    private final CorrelationResultEmitter resultEmitter;
    private final AlarmStatusEmitter statusEmitter;
    private final CorrelationMetrics metrics;

    /** (trailId :: patternId) -> live instance. At most one per pair (AC16 invariant). */
    private final Map<String, CorrelationInstance> instances = new LinkedHashMap<>();
    /** trailId -> alarms seen with no covering pattern instance (codebook fallback buffer). */
    private final Map<String, List<ObservedAlarm>> uncoveredByTrail = new LinkedHashMap<>();
    /** Ingest-side dedupe of alarms across the whole engine (AC16). */
    private final Set<String> processedAlarmIds = new LinkedHashSet<>();

    public CorrelationEngine(
            PatternStore patternStore,
            CodebookStore codebookStore,
            CodebookDecoder codebookDecoder,
            ConflictResolver conflictResolver,
            IncidentFactory incidentFactory,
            IncidentRepository incidentRepository,
            KnowledgeParamsProvider knowledgeParams,
            CorrelationResultEmitter resultEmitter,
            AlarmStatusEmitter statusEmitter,
            CorrelationMetrics metrics) {
        this.patternStore = patternStore;
        this.codebookStore = codebookStore;
        this.codebookDecoder = codebookDecoder;
        this.conflictResolver = conflictResolver;
        this.incidentFactory = incidentFactory;
        this.incidentRepository = incidentRepository;
        this.knowledgeParams = knowledgeParams;
        this.resultEmitter = resultEmitter;
        this.statusEmitter = statusEmitter;
        this.metrics = metrics;
    }

    private static String key(String trailId, String patternId) {
        return trailId + "::" + patternId;
    }

    /**
     * Process one validated, deduped live alarm at wall-clock {@code nowEpochMs}. Fans the alarm
     * out independently to each trail in {@code trailIds}, driving lazy-init / add-to-existing /
     * incremental match on every active pattern for that trail. Duplicate {@code alarmId}s are a
     * no-op (AC16).
     */
    public synchronized void onAlarm(ObservedAlarm alarm, List<String> trailIds, long nowEpochMs) {
        if (!processedAlarmIds.add(alarm.alarmId())) {
            return; // duplicate ingest — already processed (AC16)
        }
        metrics.incrementAlarmsProcessed();
        for (String trailId : trailIds) {
            dispatchToTrail(alarm, trailId, nowEpochMs);
        }
        metrics.setActiveInstances(instances.size());
    }

    private void dispatchToTrail(ObservedAlarm alarm, String trailId, long nowEpochMs) {
        List<PatternRef> patterns = patternStore.activePatternsOn(trailId);
        boolean covered = false;
        for (PatternRef pattern : patterns) {
            if (applyToPattern(alarm, trailId, pattern, nowEpochMs)) {
                covered = true;
            }
        }
        if (!covered) {
            // no active/opening pattern instance covers this alarm — buffer for codebook decode
            uncoveredByTrail.computeIfAbsent(trailId, t -> new ArrayList<>()).add(alarm);
        }
    }

    /** @return true if the alarm was admitted to / opened an instance for this pattern. */
    private boolean applyToPattern(ObservedAlarm alarm, String trailId, PatternRef pattern,
            long nowEpochMs) {
        String k = key(trailId, pattern.patternId());
        CorrelationInstance instance = instances.get(k);
        if (instance != null) {
            if (instance.alreadyAdmitted(alarm.alarmId())) {
                return true; // idempotent re-admit guard (AC16)
            }
            if (!instance.relevant(alarm.alarmType())) {
                return false; // unrelated to this pattern — natural noise rejection
            }
            instance.admit(alarm, nowEpochMs);
            statusEmitter.fireInProgress(alarm.alarmId(), nowEpochMs);
            metrics.incrementStatusChanged("in-progress");
            evaluateFullMatch(instance, nowEpochMs);
            return true;
        }
        // no instance yet — does this alarm open one? (lazy init, AC1)
        if (alarm.alarmType().equals(pattern.openingAlarmType())) {
            CorrelationInstance created = new CorrelationInstance(trailId, pattern, nowEpochMs);
            created.admit(alarm, nowEpochMs);
            instances.put(k, created);
            statusEmitter.fireInProgress(alarm.alarmId(), nowEpochMs);
            metrics.incrementStatusChanged("in-progress");
            evaluateFullMatch(created, nowEpochMs);
            return true;
        }
        return false;
    }

    /** Re-evaluate the decisive match condition immediately (AC3); fire-and-destroy on full match. */
    private void evaluateFullMatch(CorrelationInstance instance, long nowEpochMs) {
        MatchParams params = knowledgeParams.current();
        int required = Math.max(1, instance.sequenceLength() - params.partialMatchTolerance());
        if (instance.matchedCount() >= required) {
            MatchCandidate candidate = new MatchCandidate(
                    MatchCandidate.MatchType.PATTERN,
                    instance.trailId(),
                    instance.patternRef().rootCauseAlarmType(),
                    instance.matchedAlarms(),
                    instance.patternRef().confidence(),
                    instance.patternId(),
                    null);
            Optional<MatchCandidate> winner = conflictResolver.resolve(List.of(candidate), params);
            winner.ifPresent(w -> fireIncident(w, nowEpochMs));
            // destroy the instance regardless of whether a root cause could be named (AC4 lifecycle)
            instances.remove(key(instance.trailId(), instance.patternId()));
        }
    }

    /** Persist-then-emit; fire correlated for root-cause + children; count. */
    private void fireIncident(MatchCandidate winner, long nowEpochMs) {
        Optional<Incident> built = incidentFactory.build(winner);
        if (built.isEmpty()) {
            return; // no alarm carries the rootCauseAlarmType token — discard, no wrong incident
        }
        Incident incident = built.get();
        boolean inserted = incidentRepository.save(incident);
        if (!inserted) {
            return; // duplicate fingerprint — idempotent no-op (AC16)
        }
        resultEmitter.emit(incident);
        statusEmitter.fireCorrelated(incident.rootCauseAlarmId(), nowEpochMs);
        metrics.incrementStatusChanged("correlated");
        for (String child : incident.childAlarmIds()) {
            statusEmitter.fireCorrelated(child, nowEpochMs);
            metrics.incrementStatusChanged("correlated");
        }
        metrics.incrementIncidentsCreated();
        if (winner.matchType() == MatchCandidate.MatchType.PATTERN) {
            metrics.incrementPatternMatch();
        } else {
            metrics.incrementCodebookMatch();
        }
    }

    /**
     * Wall-clock tick: expire every instance whose deadline has passed without a full match
     * (destroy, revert-open, optional codebook salvage), and run codebook decode on the per-trail
     * uncovered buffers (cold-start / no-pattern fallback). Mirrors the {@code ExpiryPunctuator} +
     * uncovered-buffer decode cadence.
     */
    public synchronized void onClockTick(long nowEpochMs) {
        expireDueInstances(nowEpochMs);
        decodeUncoveredBuffers(nowEpochMs);
        metrics.setActiveInstances(instances.size());
    }

    private void expireDueInstances(long nowEpochMs) {
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, CorrelationInstance> e : instances.entrySet()) {
            if (e.getValue().deadlineEpochMs() <= nowEpochMs) {
                due.add(e.getKey());
            }
        }
        for (String k : due) {
            CorrelationInstance instance = instances.remove(k);
            if (instance == null) {
                continue;
            }
            metrics.incrementSessionExpiration();
            for (ObservedAlarm a : instance.matchedAlarms()) {
                statusEmitter.fireRevertedOpen(a.alarmId(), nowEpochMs);
                metrics.incrementStatusChanged("reverted-open");
            }
            // salvage decode: a partially-correlated set may still match a codebook scenario
            salvageDecode(instance, nowEpochMs);
        }
    }

    private void salvageDecode(CorrelationInstance instance, long nowEpochMs) {
        MatchParams params = knowledgeParams.current();
        List<TrailScenarioSignature> scenarios = codebookStore.signaturesForTrail(instance.trailId());
        Optional<MatchCandidate> candidate = codebookDecoder.decode(
                instance.trailId(), instance.matchedAlarms(), scenarios, params);
        candidate.flatMap(c -> conflictResolver.resolve(List.of(c), params))
                .ifPresent(w -> fireIncident(w, nowEpochMs));
    }

    private void decodeUncoveredBuffers(long nowEpochMs) {
        if (uncoveredByTrail.isEmpty()) {
            return;
        }
        MatchParams params = knowledgeParams.current();
        List<String> trails = new ArrayList<>(uncoveredByTrail.keySet());
        for (String trailId : trails) {
            List<ObservedAlarm> observed = uncoveredByTrail.remove(trailId);
            if (observed == null || observed.isEmpty()) {
                continue;
            }
            List<TrailScenarioSignature> scenarios = codebookStore.signaturesForTrail(trailId);
            codebookDecoder.decode(trailId, observed, scenarios, params)
                    .flatMap(c -> conflictResolver.resolve(List.of(c), params))
                    .ifPresent(w -> fireIncident(w, nowEpochMs));
        }
    }

    /** @return the number of live instances (test/observability introspection). */
    public synchronized int activeInstanceCount() {
        return instances.size();
    }

    /**
     * @return the count of distinct live {@code alarmId}s processed (post-dedupe) — the
     *     {@code totalAlarmsProcessed} denominator of the auto-correlation rate (D1). Self-contained
     *     engine state so the shown number is reproducible.
     */
    public synchronized long totalAlarmsProcessed() {
        return processedAlarmIds.size();
    }

    /** @return true if a live instance exists for {@code (trailId, patternId)} (test introspection). */
    public synchronized boolean hasInstance(String trailId, String patternId) {
        return instances.containsKey(key(trailId, patternId));
    }
}
