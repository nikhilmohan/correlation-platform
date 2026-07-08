package com.acp.patternmanager.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Documented defaults for the seed config — applied when unset, never hard-coded in the loader. */
class PatternSeedPropertiesTest {

    @Test
    void appliesDocumentedDefaultsWhenUnset() {
        PatternSeedProperties p = new PatternSeedProperties(null, null, null);
        assertThat(p.onStartup()).isTrue();
        assertThat(p.pack()).isEqualTo("seed/core-ip-patterns.json");
        assertThat(p.emitApprovedEvents()).isTrue();
    }

    @Test
    void blankPackFallsBackToDefault() {
        PatternSeedProperties p = new PatternSeedProperties(false, "  ", false);
        assertThat(p.onStartup()).isFalse();
        assertThat(p.pack()).isEqualTo("seed/core-ip-patterns.json");
        assertThat(p.emitApprovedEvents()).isFalse();
    }

    @Test
    void honoursExplicitOverrides() {
        PatternSeedProperties p = new PatternSeedProperties(true, "seed/other.json", true);
        assertThat(p.pack()).isEqualTo("seed/other.json");
    }
}
