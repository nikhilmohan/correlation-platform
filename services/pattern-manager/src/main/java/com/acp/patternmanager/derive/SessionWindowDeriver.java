package com.acp.patternmanager.derive;

import com.acp.patternmanager.config.SessionWindowProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Derives the per-pattern {@code sessionWindow = {windowMs, type}} deterministically and PURELY from
 * the mined {@code PatternMinedEvent.timing} statistics — with NO Knowledge/Topology/Codebook input
 * (design "Session-window derivation", OQ-5). It is a pure function of the timing map plus the
 * documented, env-overridable {@link SessionWindowProperties} constants; the same {@code timing}
 * always yields the same window (criterion 18). Runs once at intake; the persisted value is reused
 * verbatim by both emitted events (criterion 20).
 *
 * <p><b>Pinned timing keys (all milliseconds, P2-GAP-05 / Q11).</b> The deriver reads, directly and
 * with no aliasing by default:
 * <ul>
 *   <li>{@code timeframeMs} (primary; the observed span, drives window length),
 *   <li>{@code medianInterArrivalMs} (the {@code cv} denominator, drives {@code type}),
 *   <li>{@code maxInterArrivalMs} (optional; the gap-floor input),
 *   <li>{@code stddevInterArrivalMs} (optional; the {@code cv} numerator).
 * </ul>
 * The Pattern Miner emits exactly these four ms keys, so {@code timingAliases} defaults to empty
 * (identity) — the alias map is an opt-in escape hatch only.
 *
 * <p><b>windowMs formula.</b>
 * {@code windowMs = clamp(max(ceil(timeframeMs * marginFactor),
 * ceil(maxInterArrivalMs * gapFloorFactor)), minMs, maxMs)}. If {@code timeframeMs} is
 * absent/non-positive, the base falls back to {@code minMs} (then still gap-floored + clamped) —
 * guaranteeing a valid {@code windowMs > 0} for every pattern (never throws, never blocks persist).
 *
 * <p><b>type selection.</b> {@code cv = stddevInterArrivalMs / medianInterArrivalMs}; {@code cv <
 * cvFixedThreshold} -> {@code fixed}; {@code cv >= threshold} or spread unknown -> {@code gap-based}
 * (the safe default). The threshold test is strict ({@code <}), so {@code cv == threshold} -> {@code
 * gap-based}.
 */
@Component
public class SessionWindowDeriver {

    private static final Logger log = LoggerFactory.getLogger(SessionWindowDeriver.class);

    private static final String KEY_TIMEFRAME = "timeframeMs";
    private static final String KEY_MEDIAN = "medianInterArrivalMs";
    private static final String KEY_MAX = "maxInterArrivalMs";
    private static final String KEY_STDDEV = "stddevInterArrivalMs";
    /** Suffix marking an alias value as seconds (triggers the x1000 seconds-to-ms normalisation). */
    private static final String SECONDS_SUFFIX = ":seconds";

    private final SessionWindowProperties params;

    public SessionWindowDeriver(SessionWindowProperties params) {
        this.params = params;
    }

    /**
     * Derive the session window from a raw {@code timing} map (as carried on the mined event).
     *
     * @param timing the mined timing statistics (may be null / thin — fallbacks apply)
     * @return the derived window; {@code windowMs} is always a positive integer within [min, max]
     */
    public DerivedSessionWindow derive(Map<String, Object> timing) {
        Map<String, Object> t = applyAliases(timing);

        Double timeframeMs = number(t.get(KEY_TIMEFRAME));
        Double maxInterArrivalMs = number(t.get(KEY_MAX));
        Double medianInterArrivalMs = number(t.get(KEY_MEDIAN));
        Double stddevInterArrivalMs = number(t.get(KEY_STDDEV));

        long base;
        if (timeframeMs != null && timeframeMs > 0) {
            base = (long) Math.ceil(timeframeMs * params.marginFactor());
        } else {
            // Insufficient-timing fallback (OQ-5): a valid pattern must not be lost.
            base = params.minMs();
            log.debug("session-window fallback: timeframeMs absent/non-positive, base={}ms", base);
        }

        if (maxInterArrivalMs != null && maxInterArrivalMs > 0) {
            long gapFloor = (long) Math.ceil(maxInterArrivalMs * params.gapFloorFactor());
            base = Math.max(base, gapFloor);
        }

        long windowMs = clamp(base, params.minMs(), params.maxMs());

        DerivedSessionWindow.WindowType type = selectType(medianInterArrivalMs, stddevInterArrivalMs);

        log.debug("derived sessionWindow windowMs={} type={} (timeframeMs={}, max={}, median={}, stddev={})",
                windowMs, type.wire(), timeframeMs, maxInterArrivalMs, medianInterArrivalMs,
                stddevInterArrivalMs);
        return new DerivedSessionWindow(windowMs, type);
    }

    private DerivedSessionWindow.WindowType selectType(Double median, Double stddev) {
        if (median != null && median > 0 && stddev != null) {
            double cv = stddev / median;
            if (cv < params.cvFixedThreshold()) {
                return DerivedSessionWindow.WindowType.FIXED;
            }
        }
        // Bursty/variable OR spread unknown -> gap-based (the safe default).
        return DerivedSessionWindow.WindowType.GAP_BASED;
    }

    /**
     * Apply the optional alias/unit-normalisation map (default empty = identity). A value ending in
     * {@code :seconds} maps the source key to the target key AND multiplies by 1000 (seconds->ms).
     */
    private Map<String, Object> applyAliases(Map<String, Object> timing) {
        Map<String, Object> src = timing != null ? timing : Map.of();
        if (params.timingAliases().isEmpty()) {
            return src;
        }
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>(src);
        for (Map.Entry<String, String> alias : params.timingAliases().entrySet()) {
            String fromKey = alias.getKey();
            if (!src.containsKey(fromKey)) {
                continue;
            }
            String target = alias.getValue();
            boolean seconds = target.endsWith(SECONDS_SUFFIX);
            if (seconds) {
                target = target.substring(0, target.length() - SECONDS_SUFFIX.length());
            }
            Double v = number(src.get(fromKey));
            if (v != null) {
                out.put(target, seconds ? v * 1000.0 : v);
            }
        }
        return out;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Double number(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
