package com.acp.topology.meta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints {@code snapshotId} (honours producer-supplied, else mints UUID-based) and owns the
 * PostgreSQL current/previous bookkeeping. The {@link #cutOver} runs in ONE transaction — the
 * atomic cut-over that makes a snapshot {@code current} (Task 3, cross-store atomicity step 2).
 */
@Service
public class SnapshotMetadataService {

    private final SnapshotRepository repository;

    public SnapshotMetadataService(SnapshotRepository repository) {
        this.repository = repository;
    }

    /** Honour producer-supplied id (AC-8); else mint a unique non-empty id (AC-9). */
    public String resolveSnapshotId(String producerSupplied) {
        if (producerSupplied != null && !producerSupplied.isBlank()) {
            return producerSupplied;
        }
        return "SNAP-" + UUID.randomUUID();
    }

    /** True when no snapshot yet exists for the domain (first ingest → full-load, AC-15). */
    public boolean isFirstIngest(String domain) {
        return repository.findCurrent(domain).isEmpty() && repository.listByDomain(domain).isEmpty();
    }

    /**
     * The atomic cut-over (single PostgreSQL transaction): insert the new row as current, demote the
     * prior current to previous, evict the prior previous row. Returns the evicted snapshotId (if
     * any) so the caller can delete its NebulaGraph data AFTER the commit.
     */
    @Transactional
    public Optional<String> cutOver(SnapshotRecord newCurrent) {
        Optional<String> evicted = repository.findPreviousSnapshotId(newCurrent.domain());
        evicted.ifPresent(repository::deleteBySnapshotId);
        repository.demoteCurrentToPrevious(newCurrent.domain());
        repository.insert(newCurrent);
        return evicted;
    }

    public void recordEventId(String snapshotId, String eventId) {
        repository.setEventId(snapshotId, eventId);
    }

    public List<SnapshotRecord> listByDomain(String domain) {
        return repository.listByDomain(domain);
    }

    public Optional<SnapshotRecord> findCurrent(String domain) {
        return repository.findCurrent(domain);
    }

    /** Any current snapshot, regardless of domain (used to infer the single MVP domain). */
    public Optional<SnapshotRecord> findCurrentAnyDomain() {
        return repository.findAnyCurrent();
    }

    public Optional<SnapshotRecord> findById(String snapshotId) {
        return repository.findById(snapshotId);
    }

    public List<String> allSnapshotIds() {
        return repository.allSnapshotIds();
    }

    public SnapshotRecord build(String snapshotId, String changeType, String domain,
            int fileSchemaVersion, int nodeCount, int edgeCount, String producerSuppliedId,
            String traceId) {
        return new SnapshotRecord(snapshotId, changeType, domain, fileSchemaVersion, nodeCount,
                edgeCount, "current", producerSuppliedId, Instant.now(), null, traceId);
    }
}
