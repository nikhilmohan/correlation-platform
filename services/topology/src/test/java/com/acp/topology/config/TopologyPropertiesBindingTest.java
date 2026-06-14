package com.acp.topology.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.topology.api.QueryController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

/**
 * #221 — config-precedence regression guard.
 *
 * <p>PR #218 raised the traversal maxDepth cap 8 → 32 in three places (QueryController {@code @Max},
 * the {@link TopologyProperties.Traversal} field default, and the published {@code openapi.json}) but
 * MISSED {@code application.yml}, whose {@code ${TOPOLOGY_TRAVERSAL_MAX_DEPTH:8}} env-var fallback
 * <b>overrides the Java field initializer</b> when Spring binds the yml — so the RUNNING service still
 * capped at 8 and rejected trail-builder's {@code maxDepth=12}. The old {@code QueryServiceTest} used
 * {@code new TopologyProperties()} (the field default, 32) and so never exercised the yml override.
 *
 * <p>This test binds {@link TopologyProperties} from the <b>actual</b> {@code application.yml} on the
 * classpath (the same resource Spring loads at runtime, with the env var UNSET so the {@code :default}
 * fallback is exercised — exactly the path #218 missed) and asserts the <b>effective</b>
 * {@code traversal.max-depth} is 32. It then cross-checks that 32 agrees with the controller
 * {@code @Max} and the published {@code openapi.json maximum}, so any future divergence between
 * application.yml and the Java/openapi cap FAILS in CI rather than only at the live gate.
 */
class TopologyPropertiesBindingTest {

    private static final int EXPECTED_CAP = 32;

    @EnableConfigurationProperties(TopologyProperties.class)
    static class Config {
    }

    /**
     * Bind from the real main {@code application.yml} (env var unset → {@code :default} fallback
     * applies) and assert the EFFECTIVE bound cap is 32 — this is the assertion that fails if
     * application.yml's fallback diverges from the Java/openapi cap again.
     */
    @Test
    void effectiveTraversalMaxDepthFromApplicationYmlIs32() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                // base-url is (required) with no code default; supply a throwaway so binding succeeds.
                .withPropertyValues("topology.knowledge.base-url=http://knowledge.test:8080")
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addLast(realApplicationYml()))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    TopologyProperties props = ctx.getBean(TopologyProperties.class);
                    assertThat(props.getTraversal().getMaxDepth())
                            .as("effective traversal.max-depth bound from the real application.yml "
                                    + "(env var unset → :default fallback) must equal the cap %d; if "
                                    + "this fails, application.yml's ${TOPOLOGY_TRAVERSAL_MAX_DEPTH:..} "
                                    + "fallback has diverged from the Java field / @Max / openapi cap "
                                    + "(see #221)", EXPECTED_CAP)
                            .isEqualTo(EXPECTED_CAP);
                });
    }

    /**
     * An explicit env override still wins (the value stays env-configurable; #221 only changed the
     * default), and a 12 from trail-builder binds fine within the 32 cap.
     */
    @Test
    void envOverrideStillWinsOverYmlDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        "topology.knowledge.base-url=http://knowledge.test:8080",
                        // simulate TOPOLOGY_TRAVERSAL_MAX_DEPTH=12 winning over the yml :default
                        "topology.traversal.max-depth=12")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(TopologyProperties.class).getTraversal().getMaxDepth())
                            .isEqualTo(12);
                });
    }

    /** The controller {@code @Max} annotation must agree with the bound cap (single number, 32). */
    @Test
    void controllerMaxAnnotationAgreesWithCap() throws Exception {
        long max = -1;
        for (var method : QueryController.class.getDeclaredMethods()) {
            for (Parameter p : method.getParameters()) {
                Max m = p.getAnnotation(Max.class);
                if (m != null) {
                    max = m.value();
                }
            }
        }
        assertThat(max)
                .as("QueryController maxDepth @Max must equal the cap %d (see #221)", EXPECTED_CAP)
                .isEqualTo(EXPECTED_CAP);
    }

    /** The published openapi.json maxDepth maximum must agree with the bound cap (32). */
    @Test
    void publishedOpenApiMaximumAgreesWithCap() throws Exception {
        Path openapi = projectRoot().resolve("openapi.json");
        assertThat(Files.exists(openapi)).as("checked-in openapi.json present").isTrue();
        JsonNode root = new ObjectMapper().readTree(Files.readString(openapi));
        JsonNode params = root.path("paths").path("/topology/traversal").path("get")
                .path("parameters");
        int maximum = -1;
        for (JsonNode p : params) {
            if ("maxDepth".equals(p.path("name").asText())) {
                maximum = p.path("schema").path("maximum").asInt(-1);
            }
        }
        assertThat(maximum)
                .as("openapi.json /topology/traversal maxDepth maximum must equal the cap %d (#221)",
                        EXPECTED_CAP)
                .isEqualTo(EXPECTED_CAP);
    }

    private static Path projectRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("src/main/java/com/acp/topology"))) {
            return cwd;
        }
        return cwd.resolve("services/topology");
    }

    private static PropertySource<?> realApplicationYml() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            // The real application.yml has no profile documents, so a single source is loaded.
            return sources.get(0);
        } catch (Exception e) {
            throw new IllegalStateException("could not load main application.yml on the classpath", e);
        }
    }
}
