package com.acp.patternmanager.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [ANCHOR-CONSOL] The PURE, stateless aggregation rules that fold a new contributing mined event
 * into an existing anchored pattern's running aggregates. Every rule is a deterministic function of
 * the running aggregate + the new contributor, so folding the same DISTINCT contributing-event set
 * in any order yields the identical result (order-independence — AC-C6):
 *
 * <ul>
 *   <li><b>occurrences / instanceCount</b> — plain SUM {@code N + n_e} (total observed across sub-runs);
 *   <li><b>support / confidence / lift</b> — occurrence-weighted mean
 *       {@code (v_old * N + v_e * n_e) / (N + n_e)} (ratios are not additive; a weighted mean gives
 *       the corpus-level value and avoids double-counting);
 *   <li><b>timing</b> — per-key occurrence-weighted mean of the ms sub-keys, EXCEPT
 *       {@code maxInterArrivalMs} which is the MAX;
 *   <li><b>representative sequence</b> — kept by the caller (highest occurrence-weighted-support
 *       contributor, tie-broken longest then lexicographic — {@link #shouldReplaceRepresentative}).
 * </ul>
 *
 * <p>The weighted-mean lift combiner is a deliberate contract-avoiding choice: re-deriving lift from
 * raw joint/marginal counts would need counts the frozen {@code PatternMinedEvent} does not carry.
 */
public final class PatternAggregator {

    /** The ms timing sub-key aggregated as MAX rather than as a weighted mean. */
    static final String KEY_MAX_INTER_ARRIVAL = "maxInterArrivalMs";

    private PatternAggregator() {
    }

    /** Occurrence-weighted mean of a ratio metric ({@code support}/{@code confidence}/{@code lift}). */
    public static double weightedMean(double oldValue, int oldOcc, double newValue, int newOcc) {
        long total = (long) oldOcc + newOcc;
        if (total == 0) {
            return newValue;
        }
        return (oldValue * oldOcc + newValue * newOcc) / total;
    }

    /**
     * Combine two timing maps: each numeric ms key is an occurrence-weighted mean of the two, except
     * {@code maxInterArrivalMs} which is the MAX. Keys present in only one side pass through. The
     * result carries the same key set so {@code SessionWindowDeriver.derive} can re-run on it.
     *
     * @param oldTiming the running aggregate timing (may be null/empty on first contributor)
     * @param oldOcc the running occurrence count backing {@code oldTiming}
     * @param newTiming the new contributor's timing
     * @param newOcc the new contributor's occurrence count
     * @return the combined timing map
     */
    public static Map<String, Object> combineTiming(Map<String, Object> oldTiming, int oldOcc,
            Map<String, Object> newTiming, int newOcc) {
        Map<String, Object> old = oldTiming != null ? oldTiming : Map.of();
        Map<String, Object> neu = newTiming != null ? newTiming : Map.of();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();

        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(old.keySet());
        keys.addAll(neu.keySet());

        for (String key : keys) {
            Double a = number(old.get(key));
            Double b = number(neu.get(key));
            if (a == null && b == null) {
                // Non-numeric key present on one side — keep the new value (or old if absent).
                out.put(key, neu.containsKey(key) ? neu.get(key) : old.get(key));
                continue;
            }
            if (a == null) {
                out.put(key, b);
                continue;
            }
            if (b == null) {
                out.put(key, a);
                continue;
            }
            if (KEY_MAX_INTER_ARRIVAL.equals(key)) {
                out.put(key, Math.max(a, b));
            } else {
                out.put(key, weightedMean(a, oldOcc, b, newOcc));
            }
        }
        return out;
    }

    /**
     * Decide whether the new contributor's sequence should REPLACE the current representative. The
     * representative is the contributor with the highest occurrence-weighted support, tie-broken by
     * longest sequence, then lexicographic (all deterministic — stable under any arrival order).
     *
     * @param newWeighted the new contributor's occurrence-weighted support ({@code support * occ})
     * @param newSeq the new contributor's sequence
     * @param currentWeighted the current representative's occurrence-weighted support
     * @param currentSeq the current representative's sequence
     * @return true if the new contributor becomes the representative
     */
    public static boolean shouldReplaceRepresentative(double newWeighted, java.util.List<String> newSeq,
            double currentWeighted, java.util.List<String> currentSeq) {
        if (newWeighted > currentWeighted) {
            return true;
        }
        if (newWeighted < currentWeighted) {
            return false;
        }
        // Tie on weighted support -> longer sequence wins.
        if (newSeq.size() != currentSeq.size()) {
            return newSeq.size() > currentSeq.size();
        }
        // Tie on length -> lexicographic (smaller joined form wins, deterministic).
        return String.join(",", newSeq).compareTo(String.join(",", currentSeq)) < 0;
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
