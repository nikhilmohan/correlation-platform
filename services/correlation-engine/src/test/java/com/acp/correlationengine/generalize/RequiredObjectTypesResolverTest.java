package com.acp.correlationengine.generalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.WindowType;
import com.acp.correlationengine.support.FakeTrailBuilderClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link RequiredObjectTypesResolver} — the anti-over-match guard (spec AC39 / OQ-G2 resolved).
 * Proves the three resolution paths:
 *
 * <ul>
 *   <li><b>Normal:</b> required object types resolve directly from the pattern's
 *       {@code sampleAlarms[].managedObjectId} prefixes ({@code alarmType -> objectType} witnesses).</li>
 *   <li><b>Discovery-trail fallback:</b> when a sequence {@code alarmType} has no sample witness, the
 *       discovery trail's member object types supply the required set.</li>
 *   <li><b>Fail-safe exclusion:</b> a pattern with NO sample witnesses AND a discovery-trail fetch
 *       that fails/empties resolves to {@link Optional#empty()} — so it is EXCLUDED from the index and
 *       can never over-match every trail.</li>
 * </ul>
 */
class RequiredObjectTypesResolverTest {

    private static PatternRef pattern(String patternId, String discoveryTrailId,
            Map<String, String> sampleObjectTypes) {
        return new PatternRef(patternId, discoveryTrailId,
                List.of("aType", "bType", "cType"), "aType", 0.9, 60_000, WindowType.GAP_BASED,
                sampleObjectTypes);
    }

    /** Normal resolution: witnesses present for every sequence type -> the required set is exactly them. */
    @Test
    void resolvesFromSampleAlarmPrefixes() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        RequiredObjectTypesResolver resolver = new RequiredObjectTypesResolver(tb);

        Optional<RequiredTypes> req = resolver.resolve(
                pattern("P", "T_disc", Map.of("aType", "A", "bType", "B", "cType", "C")));

        assertThat(req).isPresent();
        assertThat(req.get().allTypes()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(req.get().rootType()).isEqualTo("A"); // root = aType -> A
    }

    /** Discovery-trail fallback: a missing sample witness is supplied by the discovery trail's members. */
    @Test
    void fallsBackToDiscoveryTrailMembersWhenWitnessMissing() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        // The discovery trail DID host the cascade -> its members are a superset of the required types.
        tb.declareTrail("T_disc", List.of("A", "B", "C"));
        RequiredObjectTypesResolver resolver = new RequiredObjectTypesResolver(tb);

        // Only aType has a sample witness; bType/cType have none -> fall back to discovery members.
        Optional<RequiredTypes> req = resolver.resolve(
                pattern("P", "T_disc", Map.of("aType", "A")));

        assertThat(req).isPresent();
        assertThat(req.get().allTypes()).contains("A", "B", "C"); // completed from discovery members
        assertThat(req.get().rootType()).isEqualTo("A");
    }

    /** Fail-safe: no witnesses AND the discovery-trail fetch fails -> excluded (empty), never guessed. */
    @Test
    void failSafeExclusion_noWitnessAndDiscoveryFetchFails() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        tb.failTrail("T_disc"); // discovery-trail member fetch fails (5xx) -> empty
        RequiredObjectTypesResolver resolver = new RequiredObjectTypesResolver(tb);

        // No sample-alarm object-type witnesses at all.
        Optional<RequiredTypes> req = resolver.resolve(
                pattern("P", "T_disc", Map.of()));

        assertThat(req).as("unresolvable pattern must be excluded (fail-safe), not resolved").isEmpty();
    }

    /** Fail-safe end-to-end: an excluded pattern is compatible with NO trail (cannot over-match). */
    @Test
    void unresolvablePatternIsExcludedFromIndex_matchesNoTrail() {
        FakeTrailBuilderClient tb = new FakeTrailBuilderClient();
        // Two fully-populated trails exist, but the pattern has no witnesses and no discovery members.
        tb.declareTrail("T1", List.of("A", "B", "C"));
        tb.declareTrail("T2", List.of("A", "B", "C"));
        tb.failTrail("T_disc"); // discovery members unavailable

        var store = new com.acp.correlationengine.pattern.PatternStore();
        store.upsert(pattern("P", "T_disc", Map.of()));
        CompatibilityIndexService svc = new CompatibilityIndexService(
                store, tb,
                new RequiredObjectTypesResolver(tb),
                new CompatibilityEvaluator(),
                com.acp.correlationengine.observability.CorrelationMetrics.NOOP,
                "core-ip");
        svc.rebuildAll("SNAP-1", "core-ip");

        // The unresolvable pattern must not appear against any trail (anti-over-match guard).
        assertThat(svc.patternsCompatibleWith("T1")).isEmpty();
        assertThat(svc.patternsCompatibleWith("T2")).isEmpty();
        assertThat(svc.current().compatibleTrailIds("P")).isEmpty();
    }
}
