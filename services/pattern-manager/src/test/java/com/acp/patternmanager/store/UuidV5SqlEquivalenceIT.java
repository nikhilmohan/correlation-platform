package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [SIG-FOLD] Pins the V4 migration's SQL {@code pattern.uuid_v5(namespace, name)} function to the
 * running service's Java {@link UuidV5#from(String)} / {@link UuidV5#signatureIdentity} — they MUST
 * produce byte-identical UUIDs. The collapse re-keys each survivor to the SQL-computed
 * signatureIdentity; if that diverged from the Java value, the running service would look the pattern
 * up by a DIFFERENT id and create a NEW row instead of folding. This test runs V4 (so the function
 * exists) and compares SQL output to Java for a sample of names, including the exact
 * {@code seq_csv|domain|snapshotId} signature name-string form.
 *
 * <p>{@code @Tag("integration")}: run via {@code -DincludeIntegration=true}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class UuidV5SqlEquivalenceIT {

    private static final UUID NAMESPACE = UUID.fromString("6b6d1f8e-3f2a-5b7c-9d4e-1a2b3c4d5e6f");

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("correlation")
            .withUsername("correlation")
            .withPassword("correlation");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pattern-manager.kafka.enabled", () -> "false");
        registry.add("pattern-manager.integration.mode", () -> "mock");
    }

    @Autowired private DataSource dataSource;

    @Test
    void sqlUuidV5MatchesJavaForSampleNames() throws Exception {
        List<String> names = List.of(
                "",
                "a",
                "trail-1|LOS,LinkDown|w1|s1",
                "IPLinkDown,LinkDown,LinkBundleDegraded|core-ip|SNAP-2026-06-08-001",
                "A,B,A|core-ip|snap-1",
                "LOS|core-ip|");
        for (String name : names) {
            assertThat(sqlUuidV5(name))
                    .as("SQL uuid_v5 must equal Java UuidV5.from for name=%s", name)
                    .isEqualTo(UuidV5.from(name));
        }
    }

    @Test
    void sqlUuidV5MatchesJavaSignatureIdentity() throws Exception {
        // The exact signature name-string the collapse builds: seq_csv|domain|snapshotId.
        String seqCsv = "IPLinkDown,LinkDown,LinkBundleDegraded";
        String domain = "core-ip";
        String snap = "snap-42";
        String name = seqCsv + "|" + domain + "|" + snap;

        UUID sql = sqlUuidV5(name);
        UUID java = UuidV5.signatureIdentity(
                List.of("IPLinkDown", "LinkDown", "LinkBundleDegraded"), domain, snap);

        assertThat(sql).isEqualTo(java);
        assertThat(sql.version()).isEqualTo(5);
        assertThat(sql.variant()).isEqualTo(2);
    }

    private UUID sqlUuidV5(String name) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT pattern.uuid_v5(?::uuid, ?)")) {
            ps.setString(1, NAMESPACE.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }
}
