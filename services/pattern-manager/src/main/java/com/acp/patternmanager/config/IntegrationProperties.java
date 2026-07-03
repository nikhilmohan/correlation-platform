package com.acp.patternmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound integration points — collaborator base URLs + a global {@code mock|real} toggle,
 * resolved from environment config (never hard-coded). In {@code mock} mode the tests point the
 * clients at a WireMock stub generated from each collaborator's published OpenAPI; in {@code real}
 * mode they point at the live Docker Compose service. See architecture.md "Configurable
 * integration points".
 *
 * @param mode the global integration mode ({@code mock} or {@code real})
 * @param topology Topology Service integration point (RCA + structural validation)
 * @param codebook Codebook Generator integration point (reconcile + RCA override)
 * @param knowledge Knowledge Service integration point (RCA / structural-validation params)
 */
@ConfigurationProperties(prefix = "pattern-manager.integration")
public record IntegrationProperties(
        String mode,
        Endpoint topology,
        Endpoint codebook,
        Endpoint knowledge) {

    /** Whether a given endpoint (or the global default) resolves to {@code real}. */
    public boolean isReal() {
        return "real".equalsIgnoreCase(mode);
    }

    /**
     * A single collaborator endpoint.
     *
     * @param baseUrl the base URL (e.g. {@code http://topology:8080})
     * @param domain the domain scope for domain-scoped calls (Knowledge / Codebook)
     */
    public record Endpoint(String baseUrl, String domain) {
    }
}
