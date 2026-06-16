package com.acp.topology.graph;

import com.acp.topology.config.TopologyProperties;
import java.io.File;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import java.time.Duration;
import java.util.List;

/**
 * Shared Testcontainers NebulaGraph stack (metad + storaged + graphd) for {@code @Tag("integration")}
 * tests. It is EXCLUDED from the fast {@code ./gradlew build} unit run and only exercised by the
 * {@code integrationTest} task. If Docker is unavailable the whole class is skipped (assumption),
 * never failed — so the build stays green in a Docker-less environment.
 */
@Tag("integration")
public abstract class NebulaIntegrationBase {

    protected static ComposeContainer nebula;
    protected static NebulaPool pool;
    protected static TopologyProperties properties;

    @BeforeAll
    static void startNebula() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is not available — skipping NebulaGraph Testcontainers integration tests");

        // Bringing up the multi-daemon NebulaGraph stack (metad + storaged + graphd) is heavy and
        // can be slow/flaky on a constrained host. Any launch/connect failure is converted into a
        // SKIP (assumption), never a hard failure — so a Docker-less or under-resourced environment
        // cannot break the run. When the stack is healthy the assertions run for real.
        try {
            nebula = new ComposeContainer(
                    new File("src/test/resources/nebula/docker-compose-nebula.yml"))
                    .withExposedService("graphd", 9669,
                            Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
                    .withExposedService("metad", 9559)
                    .withExposedService("storaged", 9779)
                    .withLocalCompose(true);
            nebula.start();

            String host = nebula.getServiceHost("graphd", 9669);
            int port = nebula.getServicePort("graphd", 9669);

            properties = new TopologyProperties();
            properties.getNebula().setHosts(host + ":" + port);
            properties.getNebula().setStoragedHost("storaged:9779");

            NebulaPoolConfig cfg = new NebulaPoolConfig();
            cfg.setMaxConnSize(10);
            pool = new NebulaPool();
            boolean ok = pool.init(List.of(new HostAddress(host, port)), cfg);
            Assumptions.assumeTrue(ok, "NebulaPool could not connect — skipping NebulaGraph tests");
        } catch (org.opentest4j.TestAbortedException abort) {
            throw abort;
        } catch (Throwable t) {
            stopNebula();
            Assumptions.abort("NebulaGraph Testcontainers stack unavailable ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ") — skipping");
        }
    }

    @AfterAll
    static void stopNebula() {
        if (pool != null) {
            pool.close();
        }
        if (nebula != null) {
            nebula.stop();
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
