package com.acp.patternmanager.naming;

import java.util.Map;

/**
 * Deterministic, side-effect-free derivation of a readable {@code patternName} for a pattern.
 *
 * <p>The Pattern Manager is the SINGLE OWNER of pattern identity, so the readable name is COMPUTED,
 * PERSISTED and SERVED here — consumers (e.g. the web-ui) must NOT derive it client-side, or the
 * name would drift across consumers. The alarm-type label map below MIRRORS the web-ui
 * {@code ALARM_TYPE_LABELS} exactly so the two never disagree; this class is now the source of truth.
 *
 * <p>The name format is {@code "<label> Cascade · <short8>"} where the separator is a middot
 * (U+00B7) and {@code <short8>} is the first 8 hex characters of the {@code patternId} (dashes
 * stripped, lower-cased). {@code patternId} is the pattern's signatureIdentity UUID: stable and
 * deterministic, so the same cascade signature always yields the same suffix across runs.
 */
public final class PatternNaming {

    /** Middot separator (U+00B7) between the cascade name and its short-id suffix. */
    public static final String SEPARATOR = " · ";

    /**
     * Raw alarm-type token -> operator-facing label. MIRRORS the web-ui {@code ALARM_TYPE_LABELS}
     * byte-for-byte. Unknown tokens fall back to the raw token (never blank).
     */
    private static final Map<String, String> ALARM_TYPE_LABELS = Map.ofEntries(
            Map.entry("AdjDown", "Adjacency Down"),
            Map.entry("BGPPeerDown", "BGP Peer Down"),
            Map.entry("ISISAdjacencyDown", "IS-IS Adjacency Down"),
            Map.entry("OSPFAdjacencyDown", "OSPF Adjacency Down"),
            Map.entry("RouteFlap", "Route Flap"),
            Map.entry("LDPSessionDown", "LDP Session Down"),
            Map.entry("LSPDown", "LSP Down"),
            Map.entry("FRRSwitchover", "FRR Switchover"),
            Map.entry("TETunnelDown", "TE Tunnel Down"),
            Map.entry("LinkDown", "Link Down"),
            Map.entry("IPLinkDown", "IP Link Down"),
            Map.entry("FiberFault", "Fiber Fault"),
            Map.entry("LOS", "Loss of Signal"),
            Map.entry("LOF", "Loss of Frame"),
            Map.entry("InterfaceDown", "Interface Down"),
            Map.entry("PortDown", "Port Down"),
            Map.entry("PortFlap", "Port Flap"));

    private PatternNaming() {
    }

    /**
     * Readable label for an alarm-type token. Unknown/blank tokens return the raw token unchanged
     * (never blank); a null token gracefully degrades to {@code "Unknown"}.
     *
     * @param token the raw alarm-type vocab token (e.g. {@code IPLinkDown})
     * @return the readable label, or the raw token if unmapped, or {@code "Unknown"} if null/blank
     */
    public static String alarmTypeLabel(String token) {
        if (token == null || token.isBlank()) {
            return "Unknown";
        }
        return ALARM_TYPE_LABELS.getOrDefault(token, token);
    }

    /**
     * Derive the readable pattern name {@code "<label> Cascade · <short8>"}.
     *
     * <p>Defensive: if {@code patternId} is null/blank or yields fewer than 8 hex chars after
     * stripping dashes, the suffix is omitted and just {@code "<label> Cascade"} is returned (never
     * a {@code "null"} literal). {@code rootCauseAlarmType} degrades via {@link #alarmTypeLabel}.
     *
     * @param rootCauseAlarmType the RCA-designated root-cause alarm-type token
     * @param patternId the pattern's signatureIdentity UUID string
     * @return the deterministic readable name
     */
    public static String patternName(String rootCauseAlarmType, String patternId) {
        String name = alarmTypeLabel(rootCauseAlarmType) + " Cascade";
        String suffix = shortId(patternId);
        return suffix != null ? name + SEPARATOR + suffix : name;
    }

    /**
     * First 8 hex characters of {@code patternId} (dashes stripped, lower-cased), or {@code null}
     * when the id is absent/too short/not enough hex to form a meaningful suffix.
     */
    private static String shortId(String patternId) {
        if (patternId == null || patternId.isBlank()) {
            return null;
        }
        String hex = patternId.replace("-", "").toLowerCase();
        if (hex.length() < 8) {
            return null;
        }
        String candidate = hex.substring(0, 8);
        return candidate.matches("[0-9a-f]{8}") ? candidate : null;
    }
}
