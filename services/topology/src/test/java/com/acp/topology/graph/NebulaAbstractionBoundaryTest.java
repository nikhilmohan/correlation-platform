package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.topology.api.dto.EdgeDto;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.SiteDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AC-19 (NebulaGraph abstraction boundary): the nebula-java client (nGQL / {@code Session} /
 * {@code NebulaPool}) is touched ONLY inside the {@code com.acp.topology.graph} package, and the
 * config that constructs the pool. No api/DTO/meta/events/ingest class references nebula-java, and
 * the typed DTOs carry no NebulaGraph connection string, space, host, or raw nGQL/rank field.
 */
class NebulaAbstractionBoundaryTest {

    @Test
    void onlyGraphPackageReferencesNebulaJavaClient() throws IOException {
        Path mainJava = mainJavaRoot();
        try (Stream<Path> files = Files.walk(mainJava)) {
            List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(NebulaAbstractionBoundaryTest::referencesNebulaClient)
                    .filter(p -> !isAllowed(p))
                    .toList();
            assertThat(offenders)
                    .as("only graph/ (+ config that builds the pool) may touch nebula-java; "
                            + "leaks: %s", offenders)
                    .isEmpty();
        }
    }

    @Test
    void typedDtosExposeNoNebulaInternals() {
        // The response DTOs are the only graph data surface; none carries host/space/nGQL/rank.
        NodeDto node = new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1", Map.of());
        EdgeDto edge = new EdgeDto("opaque-token", "Port:p1", "Node:PE1", "HOSTED_ON", "core-ip",
                Map.of(), "SNAP-1");
        SiteDto site = new SiteDto("Site:LON", "London", 51.5, -0.12, "EU-West");

        for (Object dto : List.of(node, edge, site)) {
            String json = dto.toString().toLowerCase();
            assertThat(json)
                    .doesNotContain("9669")        // graphd port
                    .doesNotContain("9779")        // storaged port
                    .doesNotContain("nebula")      // host/space/credential hints
                    .doesNotContain("ngql")
                    .doesNotContain("vertex(")     // raw nGQL result structures
                    .doesNotContain("@rank");
        }
        // The edgeId is opaque (a reversible token), never a raw NebulaGraph rank or handle.
        assertThat(edge.edgeId()).doesNotContain("@", "rank", "9669");
    }

    private static boolean referencesNebulaClient(Path file) {
        try {
            String src = Files.readString(file);
            return src.contains("com.vesoft.nebula") || src.contains("NebulaPool");
        } catch (IOException e) {
            return false;
        }
    }

    /** Allowed to touch nebula-java: the graph package + the config bean that builds the pool. */
    private static boolean isAllowed(Path file) {
        String path = file.toString().replace('\\', '/');
        return path.contains("/com/acp/topology/graph/")
                || path.endsWith("/com/acp/topology/config/BeansConfig.java");
    }

    private static Path mainJavaRoot() {
        // Robust to the Gradle working directory: walk up to the topology project root.
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("src/main/java/com/acp/topology");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path fromRepo = cwd.resolve("services/topology/src/main/java/com/acp/topology");
        if (Files.isDirectory(fromRepo)) {
            return fromRepo;
        }
        throw new IllegalStateException("could not locate topology main source from " + cwd);
    }
}
