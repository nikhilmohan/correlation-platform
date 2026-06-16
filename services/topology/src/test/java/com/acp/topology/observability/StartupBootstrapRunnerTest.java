package com.acp.topology.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.BootstrapTransientException;
import com.acp.topology.graph.GraphAccessException;
import com.acp.topology.graph.GraphRepository;
import com.acp.topology.graph.OrphanReaper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC-34 (self-healing readiness — the test that would have caught CRIT-2): a failed bootstrap is
 * retried in the background and readiness recovers DOWN→UP automatically; it NEVER one-shot-latches
 * DOWN; the retry window is bounded by the configurable overall deadline; a fatal failure fails fast
 * without burning the deadline.
 *
 * <p>Uses an injected fake scheduler (records scheduled tasks, runs them on demand) and a fake clock
 * so the bounded retry loop is exercised deterministically without real time.
 */
class StartupBootstrapRunnerTest {

    private GraphRepository graphRepository;
    private OrphanReaper orphanReaper;
    private TopologyProperties properties;
    private AtomicLong fakeNow;
    private final List<Runnable> scheduled = new ArrayList<>();
    private CapturingScheduler scheduler;

    @BeforeEach
    void setUp() {
        graphRepository = mock(GraphRepository.class);
        orphanReaper = mock(OrphanReaper.class);
        when(orphanReaper.reap()).thenReturn(0);
        fakeNow = new AtomicLong(0L);
        scheduled.clear();

        properties = new TopologyProperties();
        properties.getNebula().setBootstrapOnStartup(true);
        properties.getNebula().setRetryBackoffMs(5_000);
        properties.getStartup().setDeadlineMs(180_000);

        // A hand-written fake scheduler: capture the retry task instead of running it on real time.
        scheduler = new CapturingScheduler(scheduled);
    }

    private StartupBootstrapRunner runner() {
        LongSupplier clock = fakeNow::get;
        return new StartupBootstrapRunner(graphRepository, orphanReaper, properties, scheduler, clock);
    }

    /** Run every captured retry task once (FIFO), clearing the queue first to avoid re-entrancy. */
    private void runScheduledRetries() {
        List<Runnable> batch = new ArrayList<>(scheduled);
        scheduled.clear();
        batch.forEach(Runnable::run);
    }

    @Test
    void failedBootstrapRetriesAndDoesNotLatchDown() {
        doThrow(new BootstrapTransientException("storaged not ONLINE yet"))
                .when(graphRepository).bootstrapSchema();

        StartupBootstrapRunner runner = runner();
        runner.onReady();

        // Readiness is DOWN but NOT latched: a retry was scheduled (self-heal), not a one-shot give-up.
        assertThat(runner.isGraphReady()).isFalse();
        assertThat(scheduled).as("a bounded background retry was scheduled").hasSize(1);
        assertThat(scheduler.scheduleCalls).as("exactly one retry scheduled").isEqualTo(1);
    }

    @Test
    void readinessFlipsUpWhenLaterAttemptSucceeds() {
        // First attempt throws transient; the scheduled retry succeeds.
        doThrow(new BootstrapTransientException("space not usable yet"))
                .doNothing()
                .when(graphRepository).bootstrapSchema();

        StartupBootstrapRunner runner = runner();
        runner.onReady();
        assertThat(runner.isGraphReady()).as("DOWN after first transient failure").isFalse();

        // Time advances a little (still well within deadline); run the scheduled retry.
        fakeNow.addAndGet(5_000);
        runScheduledRetries();

        assertThat(runner.isGraphReady()).as("flips UP automatically on the successful retry").isTrue();
        verify(graphRepository, times(2)).bootstrapSchema();
        verify(orphanReaper, atLeastOnce()).reap();
    }

    @Test
    void stopsRetryingAtOverallDeadline() {
        properties.getStartup().setDeadlineMs(10_000);
        doThrow(new BootstrapTransientException("still not ready"))
                .when(graphRepository).bootstrapSchema();

        StartupBootstrapRunner runner = runner();
        runner.onReady(); // attempt at t=0 → schedules retry
        assertThat(scheduled).hasSize(1);

        // Advance past the deadline, then run the retry: it must NOT schedule another (bounded window).
        fakeNow.set(11_000);
        runScheduledRetries();

        assertThat(runner.isGraphReady()).as("stays DOWN past deadline").isFalse();
        assertThat(scheduled).as("no further retry scheduled after deadline (bounded, S2)").isEmpty();
    }

    @Test
    void fatalFailureFailsFastWithoutRetrying() {
        doThrow(new GraphAccessException("bad config / auth rejected"))
                .when(graphRepository).bootstrapSchema();

        StartupBootstrapRunner runner = runner();
        runner.onReady();

        assertThat(runner.isGraphReady()).isFalse();
        assertThat(scheduled).as("fatal failure does not schedule a retry").isEmpty();
        assertThat(scheduler.scheduleCalls).as("no retry scheduled on fatal failure").isZero();
    }

    @Test
    void firstAttemptSucceedsReadinessUpNoRetry() {
        doNothing().when(graphRepository).bootstrapSchema();

        StartupBootstrapRunner runner = runner();
        runner.onReady();

        assertThat(runner.isGraphReady()).isTrue();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void disabledBootstrapDoesNotAttemptOrLatch() {
        properties.getNebula().setBootstrapOnStartup(false);

        StartupBootstrapRunner runner = runner();
        runner.onReady();

        assertThat(runner.isGraphReady()).isFalse();
        verify(graphRepository, never()).bootstrapSchema();
        assertThat(scheduled).isEmpty();
    }

    /**
     * A fake {@link java.util.concurrent.ScheduledExecutorService} that records the scheduled retry
     * task instead of running it on real time — so the bounded retry loop is exercised
     * deterministically. Only {@code schedule(Runnable, long, TimeUnit)} and {@code shutdownNow()}
     * are exercised by the runner; the rest are unsupported.
     */
    private static final class CapturingScheduler
            extends java.util.concurrent.AbstractExecutorService
            implements java.util.concurrent.ScheduledExecutorService {

        private final List<Runnable> captured;
        int scheduleCalls;

        CapturingScheduler(List<Runnable> captured) {
            this.captured = captured;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduleCalls++;
            captured.add(command);
            return new NoopFuture();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            // no-op
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        private static final class NoopFuture implements ScheduledFuture<Object> {
            @Override
            public long getDelay(TimeUnit unit) {
                return 0;
            }

            @Override
            public int compareTo(Delayed o) {
                return 0;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                return null;
            }
        }
    }
}
