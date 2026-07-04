package com.acp.correlationengine.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.acp.correlationengine.correlate.CorrelationEngine;
import com.acp.correlationengine.generalize.StartupSnapshotDiscovery;
import com.acp.correlationengine.knowledge.KnowledgeParamsProvider;
import com.acp.correlationengine.knowledge.KnowledgeUnavailableException;
import com.acp.correlationengine.pattern.PatternRefreshService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Startup + expiry wiring (the plumbing around the already-tested engine core).
 *
 * <p>{@link StartupBootstrapRunner} must eagerly load Knowledge params BEFORE seeding the pattern set
 * (params gate readiness; the engine never invents thresholds — AC21), and must swallow a bootstrap
 * failure so readiness is simply held (not a crash). {@link ExpiryScheduler} must advance the engine's
 * clock on each scheduled tick (the wall-clock analogue of the design's ExpiryPunctuator).
 */
class StartupAndExpiryWiringTest {

    @Test
    void bootstrap_loadsKnowledgeParamsThenSeedsPatterns() {
        KnowledgeParamsProvider knowledge = mock(KnowledgeParamsProvider.class);
        PatternRefreshService patterns = mock(PatternRefreshService.class);
        StartupSnapshotDiscovery discovery = mock(StartupSnapshotDiscovery.class);
        StartupBootstrapRunner runner = new StartupBootstrapRunner(patterns, knowledge, discovery);

        runner.bootstrap();

        InOrder inOrder = Mockito.inOrder(knowledge, patterns, discovery);
        inOrder.verify(knowledge).bootstrap();
        inOrder.verify(patterns).bootstrap();
        inOrder.verify(discovery).discoverAndBuild();
    }

    @Test
    void bootstrap_failure_isSwallowed_soReadinessIsHeldNotCrashed() {
        KnowledgeParamsProvider knowledge = mock(KnowledgeParamsProvider.class);
        PatternRefreshService patterns = mock(PatternRefreshService.class);
        StartupSnapshotDiscovery discovery = mock(StartupSnapshotDiscovery.class);
        doThrow(new KnowledgeUnavailableException("down"))
                .when(knowledge).bootstrap();
        StartupBootstrapRunner runner = new StartupBootstrapRunner(patterns, knowledge, discovery);

        // No exception escapes; pattern seed is not attempted after the params failure.
        runner.bootstrap();

        verify(knowledge).bootstrap();
        verifyNoMoreInteractions(patterns);
    }

    @Test
    void expiryScheduler_tick_advancesEngineClock() {
        CorrelationEngine engine = mock(CorrelationEngine.class);
        ExpiryScheduler scheduler = new ExpiryScheduler(engine);

        long before = System.currentTimeMillis();
        scheduler.tick();
        long after = System.currentTimeMillis();

        org.mockito.ArgumentCaptor<Long> ts = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(engine).onClockTick(ts.capture());
        assertThat(ts.getValue()).isBetween(before, after);
    }

    @Test
    void expiryScheduler_repeatedTicks_eachDriveTheEngine() {
        CorrelationEngine engine = mock(CorrelationEngine.class);
        ExpiryScheduler scheduler = new ExpiryScheduler(engine);

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        verify(engine, times(3)).onClockTick(anyLong());
    }
}
