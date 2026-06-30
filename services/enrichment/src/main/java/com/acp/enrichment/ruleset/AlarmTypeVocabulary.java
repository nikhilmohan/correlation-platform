package com.acp.enrichment.ruleset;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The canonical {@code alarmType} value space for the Core IP domain.
 *
 * <p>The vocabulary value space is <b>authored in the Knowledge Service</b>; Enrichment uses it
 * only to <b>validate</b> that every {@code alarmTypeMap} value (and fallback) is a member, so the
 * REQUIRED {@code AlarmEvent.alarmType} is always a valid token (spec criterion 16, design
 * "alarmType population rule"). The Core IP MVP set is the eight tokens listed in the spec/design;
 * it is overridable via config to keep Enrichment domain-extensible without a code change.
 */
public final class AlarmTypeVocabulary {

    /** The Core IP MVP {@code alarmTypeVocabulary} (spec lines 41-50). */
    public static final Set<String> CORE_IP = Set.of(
            "FiberFault", "LOS", "PortDown", "InterfaceDown", "LinkDown", "AdjDown", "LSPDown",
            "ReachabilityLoss");

    private final Set<String> tokens;

    /** @param tokens the valid vocabulary tokens (insertion order preserved for diagnostics) */
    public AlarmTypeVocabulary(Set<String> tokens) {
        this.tokens = new LinkedHashSet<>(tokens == null || tokens.isEmpty() ? CORE_IP : tokens);
    }

    /** @return a vocabulary over the Core IP MVP token set. */
    public static AlarmTypeVocabulary coreIp() {
        return new AlarmTypeVocabulary(CORE_IP);
    }

    /** @return {@code true} iff {@code token} is a member of the vocabulary. */
    public boolean contains(String token) {
        return token != null && tokens.contains(token);
    }

    /** @return the valid token set. */
    public Set<String> tokens() {
        return Set.copyOf(tokens);
    }
}
