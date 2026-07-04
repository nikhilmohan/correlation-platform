package com.acp.correlationengine.generalize;

import com.acp.correlationengine.model.PatternRef;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves an approved pattern's required {@code objectType} multiset — {@link RequiredTypes} —
 * from the pattern's own discovery-trail structure, with <b>no</b> affinity table, <b>no</b>
 * Knowledge dependency, and <b>no</b> contract change (spec OQ-G2 resolved, Algorithm A).
 *
 * <p>Primary source: {@code PatternView.sampleAlarms[].managedObjectId} prefixes
 * ({@code "<objectType>:<id>"}, per {@code managedObjectId.schema.json}) — the direct
 * {@code alarmType -> objectType} witness carried on {@link PatternRef#sampleAlarmObjectTypes()}.
 * This is exactly Trail Builder's {@code TrailMember.objectType} vocabulary, so a pattern's required
 * types and a trail's member types share one namespace with no mapping layer.
 *
 * <p>Fallback (rarely needed): if a sequence {@code alarmType} has no sample witness, the discovery
 * trail's member object types are consulted — the discovery trail DID host the pattern, so its member
 * set is a superset of the pattern's required types. If the root type still cannot be resolved, the
 * pattern is marked <b>unresolvable</b> and excluded from the index (fail-safe — never guessed).
 */
public final class RequiredObjectTypesResolver {

    private static final Logger log = LoggerFactory.getLogger(RequiredObjectTypesResolver.class);

    private final TrailBuilderClient trailBuilder;

    public RequiredObjectTypesResolver(TrailBuilderClient trailBuilder) {
        this.trailBuilder = trailBuilder;
    }

    /**
     * @return the pattern's {@link RequiredTypes}, or empty if the pattern's required object types
     *     (specifically the root type) cannot be resolved — in which case the pattern is excluded
     *     from the compatibility index (fail-safe: no false-positive matches).
     */
    public Optional<RequiredTypes> resolve(PatternRef pattern) {
        Map<String, String> witnessed = pattern.sampleAlarmObjectTypes();
        Set<String> sequenceTypes = new LinkedHashSet<>();
        boolean missingWitness = false;
        for (String alarmType : pattern.sequence()) {
            String objectType = witnessed.get(alarmType);
            if (objectType != null && !objectType.isBlank()) {
                sequenceTypes.add(objectType);
            } else {
                missingWitness = true;
            }
        }

        String rootType = witnessed.get(pattern.rootCauseAlarmType());

        // Fallback: consult the discovery trail's members when a sample witness is missing, or when
        // the root type has no direct witness. The discovery trail hosted the cascade, so its member
        // object-type set is a superset of what the pattern requires.
        if (missingWitness || rootType == null || rootType.isBlank()) {
            Set<String> discoveryMembers = trailBuilder
                    .getTrailMemberTypes(pattern.discoveryTrailId())
                    .orElse(Set.of());
            if (!discoveryMembers.isEmpty()) {
                // The discovery trail's members are the authoritative superset of required types.
                sequenceTypes.addAll(discoveryMembers);
                if (rootType == null || rootType.isBlank()) {
                    // A single-member discovery trail unambiguously carries the root type.
                    if (discoveryMembers.size() == 1) {
                        rootType = discoveryMembers.iterator().next();
                    }
                }
            }
        }

        if (sequenceTypes.isEmpty() || rootType == null || rootType.isBlank()) {
            log.warn("Pattern {} has unresolvable required object types (root={}, seqTypes={}); "
                    + "excluding from compatibility index (fail-safe)",
                    pattern.patternId(), rootType, sequenceTypes);
            return Optional.empty();
        }
        // The root type must be part of the required set (it is a member of the sequence).
        sequenceTypes.add(rootType);
        return Optional.of(new RequiredTypes(sequenceTypes, rootType));
    }
}
