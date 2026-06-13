package com.acp.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed-loader toggle — bound from env ({@code SEED_ON_STARTUP}). When true, the Core IP domain
 * pack is loaded through the same validated write path at startup (dogfood-validated seed).
 *
 * @param onStartup whether to load the Core IP seed pack at startup (default true)
 */
@ConfigurationProperties(prefix = "knowledge.seed")
public record SeedProperties(boolean onStartup) {

    public SeedProperties {
        // boolean defaults to false when unset; the application.yml sets the dev default to true.
    }
}
