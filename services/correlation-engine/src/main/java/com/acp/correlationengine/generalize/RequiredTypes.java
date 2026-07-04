package com.acp.correlationengine.generalize;

import java.util.Objects;
import java.util.Set;

/**
 * The structural requirement a pattern imposes on a candidate host trail: the set of member
 * {@code objectType}s the pattern's {@code alarmType} sequence needs a trail to host
 * ({@code allTypes}), and the {@code objectType} of the root-cause alarm ({@code rootType}) — the
 * type on which the cascade must be able to originate.
 *
 * <p>Derived once per approved pattern by {@link RequiredObjectTypesResolver} from the pattern's
 * discovery-trail structure ({@code PatternView.sampleAlarms[].managedObjectId} prefixes), never
 * from a hard-coded {@code alarmType}-to-{@code objectType} table (spec AC39 / OQ-G2 resolved).
 */
public record RequiredTypes(Set<String> allTypes, String rootType) {

    public RequiredTypes {
        allTypes = Set.copyOf(allTypes);
        Objects.requireNonNull(rootType, "rootType");
    }
}
