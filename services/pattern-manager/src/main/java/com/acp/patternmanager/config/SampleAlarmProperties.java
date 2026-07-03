package com.acp.patternmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sample-alarm bound configuration (spec-sample-alarms OQ-SA-4 / design DA-2). The per-pattern cap
 * {@code K} is env-overridable ({@code SAMPLE_ALARMS_CAP_K}) with a documented default — NOT a
 * hard-coded value in code. The Pattern Store defensively re-caps the persisted sample to the first
 * {@code K} entries at ingest even though the miner already caps (AC-SA-6, DA-5). The cap is
 * per-pattern total (DA-2): because the fold keeps only the first contributor's sample (DA-1), the
 * persisted sample already is a single representative occurrence's alarms.
 *
 * @param capK the per-pattern sample cap {@code K} (default {@code 10}); a non-positive value is
 *     clamped up to {@code 1} so at least the first alarm is retained
 */
@ConfigurationProperties(prefix = "pattern-manager.sample-alarms")
public record SampleAlarmProperties(Integer capK) {

    /** Canonical default (design recommendation K=10). Applied when unset in config. */
    public SampleAlarmProperties {
        capK = (capK != null && capK > 0) ? capK : 10;
    }
}
