package com.acp.patternmanager.derive;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.config.SessionWindowProperties;
import com.acp.patternmanager.derive.DerivedSessionWindow.WindowType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Session-window derivation (criteria 18, 18b, 18c, 18d + supporting). The deriver is a pure
 * function of {@code timing} + documented env params; no collaborator is called.
 */
class SessionWindowDeriverTest {

    private SessionWindowDeriver deriver(Map<String, String> aliases) {
        return new SessionWindowDeriver(new SessionWindowProperties(
                null, null, null, null, null, aliases));
    }

    private SessionWindowDeriver deriver() {
        return deriver(Map.of());
    }

    private Map<String, Object> timing(long timeframe, double max, double median, double stddev) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("timeframeMs", timeframe);
        t.put("maxInterArrivalMs", max);
        t.put("medianInterArrivalMs", median);
        t.put("stddevInterArrivalMs", stddev);
        return t;
    }

    // Criterion 18: positive integer windowMs + valid type, deterministic.
    @Test
    void derivesPositiveWindowAndValidTypeDeterministically() {
        Map<String, Object> t = timing(3000, 2000, 1000, 500);
        DerivedSessionWindow first = deriver().derive(t);
        DerivedSessionWindow second = deriver().derive(t);

        assertThat(first.windowMs()).isPositive();
        assertThat(first.type()).isIn(WindowType.GAP_BASED, WindowType.FIXED);
        assertThat(second.windowMs()).isEqualTo(first.windowMs());
        assertThat(second.type()).isEqualTo(first.type());
    }

    // Criterion 18b: pinned-key worked example is internally consistent, boundary cv=0.5 -> gap-based.
    @Test
    void pinnedTimingKeysProduceConsistentWorkedExampleAndBoundaryCv() {
        // marginBase=ceil(3000*1.5)=4500; gapFloor=ceil(2000*2.0)=4000; base=4500;
        // clamp(4500,5000,1800000)=5000; cv=500/1000=0.5, strict <0.5 false -> gap-based.
        DerivedSessionWindow w = deriver().derive(timing(3000, 2000, 1000, 500));
        assertThat(w.windowMs()).isEqualTo(5000L);
        assertThat(w.type()).isEqualTo(WindowType.GAP_BASED);
        assertThat(w.type().wire()).isEqualTo("gap-based");
    }

    // Criterion 18c: with the default empty alias map, the four real ms keys are read directly.
    @Test
    void realMinerMsKeysReadDirectlyWithDefaultEmptyAliasMap() {
        // A larger timeframe so the window is above the MIN clamp and clearly comes from timeframeMs.
        DerivedSessionWindow w = deriver().derive(timing(20_000, 4000, 2000, 200));
        // marginBase=ceil(20000*1.5)=30000; gapFloor=ceil(4000*2)=8000; base=30000; clamp->30000.
        assertThat(w.windowMs()).isEqualTo(30_000L);
        // cv=200/2000=0.1 < 0.5 -> fixed.
        assertThat(w.type()).isEqualTo(WindowType.FIXED);
    }

    // Criterion 18d: seconds alias applies only when explicitly configured (off by default).
    @Test
    void legacySecondsAliasAppliesOnlyWhenConfigured() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("meanInterArrivalSeconds", 2.0);
        legacy.put("stdDevSeconds", 0.1);
        legacy.put("timeframeSeconds", 30.0);

        // Default (empty) alias map: legacy names ignored -> timeframeMs absent -> MIN fallback.
        DerivedSessionWindow noAlias = deriver().derive(legacy);
        assertThat(noAlias.windowMs()).isEqualTo(5000L); // SESSION_WINDOW_MIN_MS fallback
        assertThat(noAlias.type()).isEqualTo(WindowType.GAP_BASED); // spread unknown (keys ignored)

        // Explicitly configured alias map with seconds->ms normalisation.
        Map<String, String> aliases = Map.of(
                "timeframeSeconds", "timeframeMs:seconds",
                "meanInterArrivalSeconds", "medianInterArrivalMs:seconds",
                "stdDevSeconds", "stddevInterArrivalMs:seconds");
        DerivedSessionWindow aliased = deriver(aliases).derive(legacy);
        // timeframeMs=30000; marginBase=ceil(30000*1.5)=45000; clamp->45000.
        assertThat(aliased.windowMs()).isEqualTo(45_000L);
        // median=2000ms, stddev=100ms; cv=0.05 < 0.5 -> fixed.
        assertThat(aliased.type()).isEqualTo(WindowType.FIXED);
    }

    // Supporting: margin + gap floor + clamp all applied (gap floor dominates here).
    @Test
    void timeframeMarginGapFloorAndClampApplied() {
        // small timeframe, large max inter-arrival: gap floor should dominate.
        DerivedSessionWindow w = deriver().derive(timing(4000, 10_000, 3000, 100));
        // marginBase=ceil(4000*1.5)=6000; gapFloor=ceil(10000*2)=20000; base=20000; clamp->20000.
        assertThat(w.windowMs()).isEqualTo(20_000L);
        // MAX clamp check
        DerivedSessionWindow big = deriver().derive(timing(5_000_000, 0, 1000, 0));
        assertThat(big.windowMs()).isEqualTo(1_800_000L);
    }

    // Supporting: type selection rule (low cv -> fixed; high cv / unknown -> gap-based).
    @Test
    void lowCvSelectsFixedHighCvAndUnknownSpreadSelectGapBased() {
        assertThat(deriver().derive(timing(10_000, 1000, 1000, 100)).type())
                .isEqualTo(WindowType.FIXED); // cv=0.1
        assertThat(deriver().derive(timing(10_000, 1000, 1000, 900)).type())
                .isEqualTo(WindowType.GAP_BASED); // cv=0.9
        Map<String, Object> noStddev = new LinkedHashMap<>();
        noStddev.put("timeframeMs", 10_000L);
        noStddev.put("medianInterArrivalMs", 1000);
        assertThat(deriver().derive(noStddev).type()).isEqualTo(WindowType.GAP_BASED); // unknown spread
    }

    // Supporting: insufficient timing (absent/zero timeframe) falls back to MIN, stays positive.
    @Test
    void missingOrZeroTimeframeFallsBackToMinMsAndStaysPositive() {
        assertThat(deriver().derive(Map.of()).windowMs()).isEqualTo(5000L);
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("timeframeMs", 0);
        assertThat(deriver().derive(zero).windowMs()).isEqualTo(5000L);
        assertThat(deriver().derive(null).windowMs()).isEqualTo(5000L);
    }
}
