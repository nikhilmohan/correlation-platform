package com.acp.patternmanager.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.config.PatternSeedProperties;
import com.acp.patternmanager.enrichment.EnrichedPattern;
import com.acp.patternmanager.event.PatternEventPublisher;
import com.acp.patternmanager.store.PatternStoreService;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the pre-approved pattern seed loader. Maps 1:1 to the task acceptance criteria:
 * <ul>
 *   <li>the shipped pack loads a set of approved patterns through the sole-writer approved path;</li>
 *   <li>load is idempotent (a pattern already present is skipped — safe restart);</li>
 *   <li>the disable toggle short-circuits without touching the store;</li>
 *   <li>each seeded pattern is authored to be Correlation-Engine compatible: every sequence
 *       alarmType has a sample-alarm objectType witness and the root alarm's objectType is present,
 *       so CE's compatibility index accepts it against a fresh topology snapshot.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PatternSeedLoaderTest {

    @Mock private PatternStoreService store;
    @Mock private PatternEventPublisher eventPublisher;

    private final ObjectMapper mapper = new ObjectMapper();

    private PatternSeedLoader loader(PatternSeedProperties props) {
        return new PatternSeedLoader(store, eventPublisher, mapper, props);
    }

    private PatternSeedProperties props(boolean onStartup, boolean emit) {
        return new PatternSeedProperties(onStartup, "seed/core-ip-patterns.json", emit);
    }

    // The shipped pack loads N (>= 5) approved patterns; each routes through persistApprovedSeed as
    // 'approved' (never draft) and emits a PatternApprovedEvent.
    @Test
    void loadsShippedPackAsApprovedAndEmitsEvents() throws Exception {
        when(store.patternExists(any(UUID.class))).thenReturn(false);
        when(store.persistApprovedSeed(any(UUID.class), any(EnrichedPattern.class), anyString(),
                any(OffsetDateTime.class))).thenReturn(new PatternEntity());

        int loaded = loader(props(true, true)).loadPack("seed/core-ip-patterns.json");

        assertThat(loaded).isGreaterThanOrEqualTo(5);
        verify(store, times(loaded)).persistApprovedSeed(any(UUID.class), any(EnrichedPattern.class),
                eq("seed"), any(OffsetDateTime.class));
        verify(eventPublisher, times(loaded)).publishApproved(any(PatternEntity.class), anyString());
    }

    // Idempotency: when every seed already exists, nothing is written and no event is emitted.
    @Test
    void skipsPatternsAlreadyPresent() throws Exception {
        when(store.patternExists(any(UUID.class))).thenReturn(true);

        int loaded = loader(props(true, true)).loadPack("seed/core-ip-patterns.json");

        assertThat(loaded).isZero();
        verify(store, never()).persistApprovedSeed(any(), any(), anyString(), any());
        verifyNoInteractions(eventPublisher);
    }

    // The emit-approved-events toggle off: patterns still persist, but no patterns.approved event.
    @Test
    void doesNotEmitApprovedEventsWhenToggledOff() throws Exception {
        when(store.patternExists(any(UUID.class))).thenReturn(false);
        when(store.persistApprovedSeed(any(), any(), anyString(), any()))
                .thenReturn(new PatternEntity());

        int loaded = loader(props(true, false)).loadPack("seed/core-ip-patterns.json");

        assertThat(loaded).isGreaterThanOrEqualTo(5);
        verifyNoInteractions(eventPublisher);
    }

    // Disable toggle: run() short-circuits without touching the store at all.
    @Test
    void disabledSeedShortCircuits() throws Exception {
        loader(props(false, true)).run(new org.springframework.boot.DefaultApplicationArguments());
        verifyNoInteractions(store);
        verifyNoInteractions(eventPublisher);
    }

    // A missing pack resource is a no-op (0 loaded), never an error.
    @Test
    void missingPackIsNoOp() throws Exception {
        int loaded = loader(props(true, true)).loadPack("seed/does-not-exist.json");
        assertThat(loaded).isZero();
        verifyNoInteractions(store);
    }

    // CE-COMPATIBILITY INVARIANT: for every seeded pattern, each sequence alarmType has a sample-alarm
    // objectType witness (managedObjectId '<objectType>:<id>' prefix) and the root type is present.
    // This is exactly what RequiredObjectTypesResolver needs to place the pattern on a fresh snapshot's
    // trails; without it the pattern is fail-safe-excluded from the compatibility index.
    @Test
    void everySeededPatternIsCorrelationEngineCompatible() throws Exception {
        when(store.patternExists(any(UUID.class))).thenReturn(false);
        when(store.persistApprovedSeed(any(), any(), anyString(), any()))
                .thenReturn(new PatternEntity());
        ArgumentCaptor<EnrichedPattern> captor = ArgumentCaptor.forClass(EnrichedPattern.class);

        loader(props(true, false)).loadPack("seed/core-ip-patterns.json");

        verify(store, times(8)).persistApprovedSeed(any(), captor.capture(), anyString(), any());
        for (EnrichedPattern p : captor.getAllValues()) {
            // Build the alarmType -> objectType witness map from sampleAlarms, mirroring the CE's
            // PatternViewMapper.sampleAlarmObjectTypes(): objectType is the managedObjectId prefix.
            var witness = new java.util.HashMap<String, String>();
            p.sampleAlarms().forEach(sa -> {
                int c = sa.managedObjectId().indexOf(':');
                assertThat(c).as("sample managedObjectId is typed '<objectType>:<id>'").isGreaterThan(0);
                witness.putIfAbsent(sa.alarmType(), sa.managedObjectId().substring(0, c));
            });
            // Every sequence alarmType must have an objectType witness.
            for (String alarmType : p.sequence()) {
                assertThat(witness).as("alarmType %s has a sample witness in %s",
                        alarmType, p.rootCauseAlarmType()).containsKey(alarmType);
            }
            // The root alarm's objectType is present (cascade origin can be hosted).
            assertThat(witness).containsKey(p.rootCauseAlarmType());
            // Root is authored as the FIRST/causal element (high RCA accuracy — not a fragment root).
            assertThat(p.sequence().get(0)).isEqualTo(p.rootCauseAlarmType());
            // Approved-seed invariants: structurally validated, confirmed, positive window.
            assertThat(p.structurallyValidated()).isTrue();
            assertThat(p.sessionWindow().windowMs()).isPositive();
        }
    }
}
