package com.acp.correlationengine.generalize;

import com.acp.correlationengine.pattern.PatternManagerClient;
import com.acp.correlationengine.topology.TopologyClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup discovery of the current topology snapshot so the compatibility index is populated
 * immediately at boot — without depending on a live {@code trails.built} event arriving.
 *
 * <p>Root cause this fixes: in a running system the {@code trails.built} events were consumed and
 * their offsets committed long before the engine restarted, so the {@code trails.built} consumer
 * never re-learns the snapshot on restart and {@link CompatibilityIndexService#rebuildAll} stays a
 * no-op (empty index -> zero auto-correlation). This runs at startup to discover the snapshot from
 * an authoritative current-state source and trigger the rebuild.
 *
 * <p>Discovery order (first hit wins):
 * <ol>
 *   <li><b>Topology Service</b> ({@code GET /topology/snapshots}, {@code status == "current"}) — the
 *       authoritative current-snapshot source.</li>
 *   <li><b>Approved patterns</b> ({@code PatternView.supportingInstances[].snapshotId}) — secondary
 *       source used only if Topology is unreachable, so the index still builds.</li>
 * </ol>
 * If neither yields a snapshot, the index is left empty (clear log, no crash) and the live
 * {@code trails.built} path recovers it later — the pre-fix behavior.
 *
 * <p>Idempotent + race-safe: the actual build goes through {@link CompatibilityIndexService#rebuildAll}
 * which is {@code synchronized} and reference-swaps atomically, so this never races the
 * {@code trails.built} consumer.
 */
public class StartupSnapshotDiscovery {

    private static final Logger log = LoggerFactory.getLogger(StartupSnapshotDiscovery.class);

    private final TopologyClient topologyClient;
    private final PatternManagerClient patternManagerClient;
    private final CompatibilityIndexService compatibilityIndex;
    private final String domain;

    public StartupSnapshotDiscovery(TopologyClient topologyClient,
            PatternManagerClient patternManagerClient, CompatibilityIndexService compatibilityIndex,
            String domain) {
        this.topologyClient = topologyClient;
        this.patternManagerClient = patternManagerClient;
        this.compatibilityIndex = compatibilityIndex;
        this.domain = domain;
    }

    /**
     * Discover the current snapshot and build the compatibility index against it. Best-effort: any
     * discovery failure is logged and swallowed so bootstrap is never aborted by it.
     */
    public void discoverAndBuild() {
        Optional<String> snapshotId = fromTopology().or(this::fromApprovedPatterns);
        if (snapshotId.isEmpty()) {
            log.warn("Startup snapshot discovery found no current snapshot "
                    + "(Topology + approved-pattern fallback both empty) — compatibility index stays "
                    + "empty until a trails.built event arrives");
            return;
        }
        log.info("Startup snapshot discovery resolved snapshot={} — building compatibility index",
                snapshotId.get());
        compatibilityIndex.noteSnapshot(snapshotId.get());
        compatibilityIndex.rebuildAll(snapshotId.get(), domain);
    }

    private Optional<String> fromTopology() {
        try {
            Optional<String> snap = topologyClient.currentSnapshotId(domain);
            if (snap.isPresent()) {
                log.info("Current topology snapshot discovered from Topology Service: {}", snap.get());
            }
            return snap;
        } catch (RuntimeException e) {
            log.warn("Topology snapshot discovery threw — falling back to approved patterns", e);
            return Optional.empty();
        }
    }

    private Optional<String> fromApprovedPatterns() {
        try {
            Optional<String> snap = patternManagerClient.discoverSnapshotId();
            if (snap.isPresent()) {
                log.info("Current snapshot derived from approved-pattern supportingInstances "
                        + "(Topology unavailable at startup): {}", snap.get());
            }
            return snap;
        } catch (RuntimeException e) {
            log.warn("Approved-pattern snapshot fallback threw — index will build on trails.built", e);
            return Optional.empty();
        }
    }
}
