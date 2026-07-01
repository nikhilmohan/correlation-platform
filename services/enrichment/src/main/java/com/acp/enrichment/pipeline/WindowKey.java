package com.acp.enrichment.pipeline;

/**
 * The windowed-state key: {@code (path, source, managedObjectId, eventType, alarmType)}. Including
 * {@code path} and {@code source} keeps history/live state and per-source windows independent so
 * per-source filter parameters apply without cross-contamination (criterion 11; design
 * "Windowed-state key").
 *
 * <p><b>Includes {@code alarmType} (Defect #7 fix).</b> A fault cascade fires MANY DISTINCT
 * {@code alarmType}s on ONE object, and in this domain many of them share ONE coarse X.733
 * {@code eventType} (e.g. {@code communicationsAlarm}: {@code AdjDown}, {@code BGPPeerDown},
 * {@code ISISAdjacencyDown}, {@code OSPFAdjacencyDown}, {@code RouteFlap}, {@code LDPSessionDown},
 * …). Keying only on {@code eventType} made flap-damp/self-clear treat these distinct cascade
 * members as oscillations/repeats of a single alarm and collapse them onto one arbitrary survivor,
 * silently eating the very sequence steps the pattern-miner needs. {@code alarmType} is the
 * canonical, finer discriminator (1:1-finer than {@code probableCause}), so including it means
 * flap-damp collapses only genuine oscillation of the SAME alarm and self-clear matches a raise
 * with a clear of the SAME alarm — never distinct cascade members that merely share an
 * {@code eventType}.
 */
public record WindowKey(Path path, String source, String managedObjectId, String eventType,
        String alarmType) {
}
