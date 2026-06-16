package com.acp.topology.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AC-8 (producer-supplied snapshotId honoured), AC-9 (mint when absent), AC-14 (re-ingest mints a
 * NEW id; prior current demoted to previous, prior previous evicted). The repository is mocked —
 * the metadata service's minting + cut-over logic is unit-tested in isolation.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotMetadataServiceTest {

    @Mock
    private SnapshotRepository repository;

    private SnapshotMetadataService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotMetadataService(repository);
    }

    @Test
    void usesProducerSuppliedSnapshotId() {
        assertThat(service.resolveSnapshotId("SNAP-FROM-PRODUCER")).isEqualTo("SNAP-FROM-PRODUCER");
    }

    @Test
    void mintsUniqueSnapshotIdWhenAbsent() {
        String a = service.resolveSnapshotId(null);
        String b = service.resolveSnapshotId("  ");
        assertThat(a).isNotBlank();
        assertThat(b).isNotBlank();
        assertThat(a).isNotEqualTo(b); // unique per mint
    }

    @Test
    void firstIngestWhenNoSnapshotsForDomain() {
        when(repository.findCurrent("core-ip")).thenReturn(Optional.empty());
        when(repository.listByDomain("core-ip")).thenReturn(List.of());
        assertThat(service.isFirstIngest("core-ip")).isTrue();
    }

    @Test
    void notFirstIngestWhenADomainSnapshotExists() {
        when(repository.findCurrent("core-ip")).thenReturn(Optional.of(record("SNAP-1", "current")));
        assertThat(service.isFirstIngest("core-ip")).isFalse();
    }

    @Test
    void cutOverDemotesPriorCurrentAndEvictsPriorPrevious() {
        // Prior previous is SNAP-OLD → it should be evicted (deleted), and its id returned so the
        // caller can delete the NebulaGraph data AFTER the PostgreSQL commit.
        when(repository.findPreviousSnapshotId("core-ip")).thenReturn(Optional.of("SNAP-OLD"));
        SnapshotRecord newCurrent = service.build("SNAP-NEW", "full-load", "core-ip", 1, 5, 4,
                null, "trace-1");

        Optional<String> evicted = service.cutOver(newCurrent);

        assertThat(evicted).contains("SNAP-OLD");
        verify(repository).deleteBySnapshotId("SNAP-OLD");
        verify(repository).demoteCurrentToPrevious("core-ip");
        verify(repository).insert(newCurrent);
    }

    @Test
    void reingestMintsNewSnapshotIdDistinctFromFirst() {
        // AC-14: a second ingest mints a new id even with identical content (no supplied id).
        String first = service.resolveSnapshotId(null);
        String second = service.resolveSnapshotId(null);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void recordEventIdDelegatesToRepository() {
        service.recordEventId("SNAP-1", "evt-123");
        verify(repository).setEventId("SNAP-1", "evt-123");
    }

    @Test
    void buildStampsCurrentStatusAndCounts() {
        SnapshotRecord r = service.build("SNAP-X", "incremental", "core-ip", 1, 7, 6,
                "SNAP-X", "trace-9");
        assertThat(r.status()).isEqualTo("current");
        assertThat(r.changeType()).isEqualTo("incremental");
        assertThat(r.nodeCount()).isEqualTo(7);
        assertThat(r.edgeCount()).isEqualTo(6);
        assertThat(r.producerSuppliedId()).isEqualTo("SNAP-X");
    }

    private static SnapshotRecord record(String id, String status) {
        return new SnapshotRecord(id, "full-load", "core-ip", 1, 1, 1, status, null, null, null,
                "t");
    }
}
