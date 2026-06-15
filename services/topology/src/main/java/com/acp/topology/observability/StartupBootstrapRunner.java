package com.acp.topology.observability;

import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.BootstrapTransientException;
import com.acp.topology.graph.GraphRepository;
import com.acp.topology.graph.OrphanReaper;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Self-healing startup bootstrap (Flow E / Algorithm §D), conforming to
 * {@code docs/startup-robustness-standard.md} S2/S3 (CRIT-2 fix).
 *
 * <p>On {@link ApplicationReadyEvent} it attempts the idempotent NebulaGraph bootstrap (via the
 * {@link GraphRepository} port) + orphan reaper. On a <strong>transient</strong> failure (dependency
 * not yet ready) it does <strong>not</strong> latch readiness DOWN forever — instead it schedules a
 * <strong>bounded background retry</strong> (backoff {@code retry-backoff-ms}) and keeps retrying
 * until the bootstrap succeeds <em>or</em> the overall {@code startup.deadline-ms} window elapses.
 * {@code graphReady} is set true <strong>only</strong> on full success, so the readiness
 * {@link NebulaGraphHealthIndicator} reflects the <strong>true current state</strong> and flips
 * <strong>UP automatically</strong> when a later attempt succeeds — never one-shot-latches DOWN.
 *
 * <p>A <strong>fatal</strong> failure (bad config / auth rejected) fails fast without burning the
 * deadline. Bootstrap can be disabled in unit tests via {@code topology.nebula.bootstrap-on-startup=false}.
 */
@Component
public class StartupBootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBootstrapRunner.class);

    private final GraphRepository graphRepository;
    private final OrphanReaper orphanReaper;
    private final boolean bootstrapEnabled;
    private final long deadlineMs;
    private final long retryBackoffMs;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;

    private final AtomicBoolean graphReady = new AtomicBoolean(false);
    private volatile long startedAtMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public StartupBootstrapRunner(GraphRepository graphRepository, OrphanReaper orphanReaper,
            TopologyProperties properties) {
        this(graphRepository, orphanReaper, properties, defaultScheduler(), System::currentTimeMillis);
    }

    /** Test seam: inject a controllable scheduler + clock so the bounded retry loop is unit-testable. */
    StartupBootstrapRunner(GraphRepository graphRepository, OrphanReaper orphanReaper,
            TopologyProperties properties, ScheduledExecutorService scheduler, LongSupplier clock) {
        this.graphRepository = graphRepository;
        this.orphanReaper = orphanReaper;
        this.bootstrapEnabled = properties.getNebula().isBootstrapOnStartup();
        this.deadlineMs = properties.getStartup().getDeadlineMs();
        this.retryBackoffMs = properties.getNebula().getRetryBackoffMs();
        this.scheduler = scheduler;
        this.clock = clock;
    }

    private static ScheduledExecutorService defaultScheduler() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "topology-bootstrap-retry");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(tf);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!bootstrapEnabled) {
            log.info("NebulaGraph bootstrap disabled (topology.nebula.bootstrap-on-startup=false)");
            return;
        }
        startedAtMillis = clock.getAsLong();
        attempt();
    }

    /**
     * One bootstrap attempt. On transient failure, schedules itself again (bounded by the overall
     * deadline) — never latches DOWN. Visible for test invocation.
     */
    void attempt() {
        try {
            graphRepository.bootstrapSchema();
            int reaped = orphanReaper.reap();
            graphReady.set(true); // readiness flips UP — true current state (S3)
            log.info("startup bootstrap complete; orphan snapshots reaped={}", reaped);
        } catch (BootstrapTransientException e) {
            // graphReady stays false but is NOT latched — a later successful attempt flips it UP (S3).
            long elapsed = clock.getAsLong() - startedAtMillis;
            if (elapsed < deadlineMs) {
                log.warn("bootstrap attempt failed (transient); retrying in {}ms (elapsed {}ms of "
                        + "deadline {}ms): {}", retryBackoffMs, elapsed, deadlineMs, e.getMessage());
                scheduleRetry();
            } else {
                log.error("startup deadline {}ms exceeded; readiness stays DOWN (bounded window)",
                        deadlineMs, e);
            }
        } catch (Exception e) {
            // Fatal (bad config / auth rejected / invalid migration): fail fast, do NOT burn deadline.
            log.error("fatal bootstrap failure; not retrying", e);
        }
    }

    private void scheduleRetry() {
        try {
            scheduler.schedule(this::attempt, retryBackoffMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Scheduler shut down (app stopping) — nothing to retry.
            log.debug("bootstrap retry not scheduled; scheduler is shut down");
        }
    }

    public boolean isGraphReady() {
        return graphReady.get();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
