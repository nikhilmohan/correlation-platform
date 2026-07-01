package com.acp.enrichment.ruleset;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The canonical {@code alarmType} value space for the Core IP domain.
 *
 * <p>The vocabulary value space is <b>authored in the Knowledge Service</b> (the single source of
 * truth); Enrichment uses it only to <b>validate</b> that every {@code alarmTypeMap} value (and
 * fallback) is a member, so the REQUIRED {@code AlarmEvent.alarmType} is always a valid token (spec
 * criterion 16, design "alarmType population rule"). At startup the vocabulary is fetched from
 * Knowledge's {@code alarm-type-vocabulary} record ({@link com.acp.enrichment.knowledge}); the
 * {@link #CORE_IP_FALLBACK} set below is only a documented fallback used when Knowledge is
 * unreachable at startup (a warning is logged — the service never silently runs on a stale/truncated
 * vocabulary).
 *
 * <p>{@link #CORE_IP_FALLBACK} is the full authoritative Core IP token list (30 tokens) — it must
 * match the Knowledge {@code core-ip/alarm-type-vocabulary/default} record so that, even in the
 * offline-fallback case, a correct ruleset (including the Simulator identity ruleset over all 30
 * tokens) still validates.
 */
public final class AlarmTypeVocabulary {

    /**
     * Documented offline fallback: the full 30-token Core IP {@code alarmTypeVocabulary}, mirroring
     * the authoritative Knowledge {@code core-ip/alarm-type-vocabulary/default} record. Used ONLY
     * when Knowledge is unreachable at startup (a warning is logged); the live source of truth is
     * the Knowledge fetch.
     */
    public static final Set<String> CORE_IP_FALLBACK = Set.of(
            "LOS", "LOF", "OpticalPowerLow", "FiberCut", "FiberFault", "PortDown", "LineCardFault",
            "CRCErrors", "PortFlapping", "LinkBundleDegraded", "NodeDown", "InterfaceDown",
            "InterfaceErrors", "IPLinkDown", "LinkDown", "ISISAdjacencyDown", "AdjDown",
            "OSPFAdjacencyDown", "BGPPeerDown", "RouteFlap", "LDPSessionDown", "LSPDown",
            "FRRSwitchover", "TETunnelDown", "VPNReachabilityLoss", "ReachabilityLoss",
            "ServiceDegraded", "Congestion", "QueueDrop", "HighLatency");

    private final Set<String> tokens;

    /** @param tokens the valid vocabulary tokens (insertion order preserved for diagnostics) */
    public AlarmTypeVocabulary(Set<String> tokens) {
        this.tokens = new LinkedHashSet<>(
                tokens == null || tokens.isEmpty() ? CORE_IP_FALLBACK : tokens);
    }

    /** @return a vocabulary over the offline Core IP fallback token set (30 tokens). */
    public static AlarmTypeVocabulary coreIpFallback() {
        return new AlarmTypeVocabulary(CORE_IP_FALLBACK);
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
