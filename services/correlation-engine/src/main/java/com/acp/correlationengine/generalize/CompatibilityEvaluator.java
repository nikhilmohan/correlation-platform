package com.acp.correlationengine.generalize;

import java.util.Set;

/**
 * The structural-compatibility rule (spec OQ-G1, resolved): <b>hostability subset, area-agnostic,
 * root-present</b>. A trail is compatible with a pattern iff the trail's member {@code objectType}
 * set is a superset of the pattern's required {@code objectType} set AND contains the root alarm's
 * {@code objectType} (so the cascade can originate on that trail).
 *
 * <p>No IGP-area / SRLG bounding and no topological-connectivity check — a learned signature
 * generalizes anywhere in the network whose structure can physically host it. This is a pure
 * predicate with no hidden state, evaluated per {@code (pattern, trail)} pair at index-build time
 * (never per alarm).
 */
public final class CompatibilityEvaluator {

    /**
     * @param req the pattern's required object types + root type
     * @param trailMemberTypes the distinct member {@code objectType}s of the candidate trail
     * @return true iff the trail can host every required object type and the root type
     */
    public boolean isCompatible(RequiredTypes req, Set<String> trailMemberTypes) {
        if (req == null || trailMemberTypes == null) {
            return false;
        }
        return trailMemberTypes.containsAll(req.allTypes())   // can host every required object type
                && trailMemberTypes.contains(req.rootType());  // and the cascade's origin type
    }
}
