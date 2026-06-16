package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acp.topology.config.TopologyProperties;
import com.acp.topology.observability.StartupBootstrapRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * AC-33 / S6 — the clean-volume cold-start test that would have caught CRIT-1, and the IT half of
 * AC-34 (self-heal). It runs against a REAL NebulaGraph (metad + storaged + graphd) brought up from
 * EMPTY volumes via Testcontainers ({@link NebulaIntegrationBase}). On a fresh stack the storaged
 * host is registered but transiently {@code OFFLINE} (~10-30s) and the space has never been created —
 * exactly the conditions under which the old "{@code ADD HOSTS} then immediate {@code CREATE SPACE}"
 * code failed with {@code Host not enough!}.
 *
 * <p>A mock-{@link com.vesoft.nebula.client.graph.net.Session} unit test structurally CANNOT catch
 * CRIT-1 — it never reproduces the ADDed-but-OFFLINE storaged window or space propagation. This IT
 * does, by exercising the real timing.
 *
 * <p>Tagged {@code integration} (via {@link NebulaIntegrationBase}); it runs in the {@code
 * ./gradlew integrationTest} task (skipped, never failed, when Docker is absent).
 */
class ColdStartReadinessIT extends NebulaIntegrationBase {

    @Test
    void createSpaceSucceedsOnlyAfterStoragedOnline() {
        // Use short-but-real deadlines that comfortably exceed the storaged ONLINE window.
        properties.getNebula().setPollIntervalMs(1000);
        properties.getNebula().setStoragedOnlineDeadlineMs(120_000);
        properties.getNebula().setSpaceUsableDeadlineMs(60_000);

        NebulaSchemaBootstrap bootstrap = new NebulaSchemaBootstrap(pool, properties);

        // On a clean volume this MUST NOT fail with "Host not enough!": the bootstrap waits for the
        // storaged host's Status == ONLINE (polling SHOW HOSTS) BEFORE issuing CREATE SPACE.
        assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
        // Idempotent re-run (S4) is a no-op.
        assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
    }

    @Test
    void reachesReadinessFromEmptyVolumesWithinDeadline() {
        properties.getNebula().setPollIntervalMs(1000);
        properties.getNebula().setStoragedOnlineDeadlineMs(120_000);
        properties.getNebula().setSpaceUsableDeadlineMs(60_000);
        properties.getStartup().setDeadlineMs(180_000);

        StartupBootstrapRunner runner = newRunner();

        runner.onReady();

        // Readiness flips UP within the configured overall deadline (the bounded window, S2/S3).
        Awaitility.await("graph readiness UP from empty volumes")
                .atMost(properties.getStartup().getDeadlineMs(), TimeUnit.MILLISECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(runner::isGraphReady);

        assertThat(runner.isGraphReady()).isTrue();
    }

    @Test
    void recoversWithoutRestartWhenDependencyBecomesReadyLate() {
        // AC-34 IT half: a first attempt that fails transiently (deadlines so short the storaged
        // ONLINE poll times out before the host comes ONLINE) must NOT latch DOWN — a later attempt
        // with a generous deadline succeeds and readiness flips UP with no restart.
        properties.getNebula().setPollIntervalMs(500);
        properties.getNebula().setStoragedOnlineDeadlineMs(1); // far too short → transient on attempt 1
        properties.getNebula().setSpaceUsableDeadlineMs(60_000);
        properties.getStartup().setDeadlineMs(180_000);
        properties.getNebula().setRetryBackoffMs(2_000);

        StartupBootstrapRunner runner = newRunner();
        runner.onReady();

        // It may already be ONLINE on a warm-enough host; if so it's UP immediately. Otherwise the
        // first attempt is transient (not latched) and the background retry will succeed once we
        // relax the deadline below.
        if (!runner.isGraphReady()) {
            properties.getNebula().setStoragedOnlineDeadlineMs(120_000);
            Awaitility.await("readiness recovers via background retry without restart")
                    .atMost(properties.getStartup().getDeadlineMs(), TimeUnit.MILLISECONDS)
                    .pollInterval(Duration.ofSeconds(1))
                    .until(runner::isGraphReady);
        }

        assertThat(runner.isGraphReady()).isTrue();
    }

    /** Wire a real GraphRepository over the live pool + a no-op reaper (the IT focuses on timing). */
    private StartupBootstrapRunner newRunner() {
        NebulaSessionProvider sessions = new NebulaSessionProvider(pool, properties);
        NebulaSchemaBootstrap bootstrap = new NebulaSchemaBootstrap(pool, properties);
        GraphRepository repo =
                new NebulaGraphRepository(sessions, bootstrap, properties, new ObjectMapper());
        OrphanReaper reaper = mock(OrphanReaper.class);
        when(reaper.reap()).thenReturn(0);
        return new StartupBootstrapRunner(repo, reaper, properties);
    }
}
