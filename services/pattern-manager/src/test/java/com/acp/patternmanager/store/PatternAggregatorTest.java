package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * [ANCHOR-CONSOL] Pure aggregation-rule tests (AC-C6 math). The rules must be occurrence-weighted
 * means for the ratio metrics, a plain sum for occurrences, MAX for {@code maxInterArrivalMs}, and a
 * deterministic representative tie-break — all order-independent.
 */
class PatternAggregatorTest {

    @Test
    void weightedMeanIsOccurrenceWeighted() {
        // support 0.4 over 10 occ, then 0.6 over 30 occ -> (0.4*10 + 0.6*30)/40 = 0.55
        double agg = PatternAggregator.weightedMean(0.4, 10, 0.6, 30);
        assertThat(agg).isCloseTo(0.55, within(1e-9));
    }

    @Test
    void weightedMeanIsOrderIndependent() {
        double forward = PatternAggregator.weightedMean(0.4, 10, 0.6, 30);
        double reverse = PatternAggregator.weightedMean(0.6, 30, 0.4, 10);
        assertThat(forward).isCloseTo(reverse, within(1e-12));
    }

    @Test
    void combineTimingIsWeightedMeanExceptMaxWhichIsMax() {
        Map<String, Object> a = Map.of(
                "timeframeMs", 10_000, "medianInterArrivalMs", 4000,
                "maxInterArrivalMs", 6000, "stddevInterArrivalMs", 1000);
        Map<String, Object> b = Map.of(
                "timeframeMs", 20_000, "medianInterArrivalMs", 5000,
                "maxInterArrivalMs", 9000, "stddevInterArrivalMs", 2000);

        // a backed by 10 occ, b by 30 occ.
        Map<String, Object> combined = PatternAggregator.combineTiming(a, 10, b, 30);

        // timeframeMs -> (10000*10 + 20000*30)/40 = 17500
        assertThat(((Number) combined.get("timeframeMs")).doubleValue()).isCloseTo(17_500.0, within(1e-6));
        // maxInterArrivalMs -> MAX(6000, 9000) = 9000
        assertThat(((Number) combined.get("maxInterArrivalMs")).doubleValue()).isCloseTo(9000.0, within(1e-6));
        // median -> (4000*10 + 5000*30)/40 = 4750
        assertThat(((Number) combined.get("medianInterArrivalMs")).doubleValue()).isCloseTo(4750.0, within(1e-6));
    }

    @Test
    void combineTimingOrderIndependent() {
        Map<String, Object> a = Map.of("timeframeMs", 10_000, "maxInterArrivalMs", 6000);
        Map<String, Object> b = Map.of("timeframeMs", 20_000, "maxInterArrivalMs", 9000);
        Map<String, Object> forward = PatternAggregator.combineTiming(a, 10, b, 30);
        Map<String, Object> reverse = PatternAggregator.combineTiming(b, 30, a, 10);
        assertThat(((Number) forward.get("timeframeMs")).doubleValue())
                .isCloseTo(((Number) reverse.get("timeframeMs")).doubleValue(), within(1e-9));
        assertThat(((Number) forward.get("maxInterArrivalMs")).doubleValue())
                .isEqualTo(((Number) reverse.get("maxInterArrivalMs")).doubleValue());
    }

    @Test
    void representativeReplacedWhenHigherWeightedSupport() {
        boolean replace = PatternAggregator.shouldReplaceRepresentative(
                5.0, List.of("a", "b"), 3.0, List.of("x"));
        assertThat(replace).isTrue();
    }

    @Test
    void representativeKeptWhenLowerWeightedSupport() {
        boolean replace = PatternAggregator.shouldReplaceRepresentative(
                2.0, List.of("a", "b"), 3.0, List.of("x"));
        assertThat(replace).isFalse();
    }

    @Test
    void representativeTieBrokenByLongerThenLexicographic() {
        // Equal weighted support -> longer sequence wins.
        assertThat(PatternAggregator.shouldReplaceRepresentative(
                3.0, List.of("a", "b", "c"), 3.0, List.of("a", "b"))).isTrue();
        // Equal weight + equal length -> lexicographically smaller wins.
        assertThat(PatternAggregator.shouldReplaceRepresentative(
                3.0, List.of("a", "a"), 3.0, List.of("a", "b"))).isTrue();
        assertThat(PatternAggregator.shouldReplaceRepresentative(
                3.0, List.of("a", "z"), 3.0, List.of("a", "b"))).isFalse();
    }
}
