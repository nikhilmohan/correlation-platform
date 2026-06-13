package com.acp.topology.graph;

import com.acp.topology.meta.SnapshotMetadataService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sweeps NebulaGraph {@code snapshotId}s that have no matching PostgreSQL row (orphans left by a
 * failed cross-store ingest). Run on startup (and callable on demand). Visibility is gated solely on
 * the PostgreSQL current pointer, so an orphan was never visible; the reaper just reclaims space.
 */
@Component
public class OrphanReaper {

    private static final Logger log = LoggerFactory.getLogger(OrphanReaper.class);

    private final GraphRepository repository;
    private final SnapshotMetadataService metadata;

    public OrphanReaper(GraphRepository repository, SnapshotMetadataService metadata) {
        this.repository = repository;
        this.metadata = metadata;
    }

    /** @return the number of orphan snapshots reaped. */
    public int reap() {
        Set<String> known = new HashSet<>(metadata.allSnapshotIds());
        List<String> inGraph = repository.distinctSnapshotIds();
        int reaped = 0;
        for (String sid : inGraph) {
            if (sid != null && !known.contains(sid)) {
                log.info("reaping orphan NebulaGraph snapshotId={} (no PostgreSQL row)", sid);
                repository.deleteSnapshot(sid);
                reaped++;
            }
        }
        return reaped;
    }
}
