package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.topology.meta.SnapshotMetadataService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AC-25 (orphan reaper): NebulaGraph snapshotIds with no matching PostgreSQL row are swept;
 * snapshotIds that DO have a PostgreSQL row are left intact. This reclaims space left by a failed
 * cross-store ingest (the orphan was never visible because visibility is gated on the PostgreSQL
 * current pointer).
 */
@ExtendWith(MockitoExtension.class)
class OrphanReaperTest {

    @Mock
    private GraphRepository repository;

    @Mock
    private SnapshotMetadataService metadata;

    private OrphanReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new OrphanReaper(repository, metadata);
    }

    @Test
    void sweepsNebulaSnapshotIdsWithNoPostgresRow() {
        when(metadata.allSnapshotIds()).thenReturn(List.of("SNAP-CURRENT", "SNAP-PREVIOUS"));
        when(repository.distinctSnapshotIds())
                .thenReturn(List.of("SNAP-CURRENT", "SNAP-PREVIOUS", "SNAP-ORPHAN-1", "SNAP-ORPHAN-2"));

        int reaped = reaper.reap();

        assertThat(reaped).isEqualTo(2);
        verify(repository).deleteSnapshot("SNAP-ORPHAN-1");
        verify(repository).deleteSnapshot("SNAP-ORPHAN-2");
        verify(repository, never()).deleteSnapshot("SNAP-CURRENT");
        verify(repository, never()).deleteSnapshot("SNAP-PREVIOUS");
    }

    @Test
    void reapsNothingWhenAllSnapshotsAreReferenced() {
        when(metadata.allSnapshotIds()).thenReturn(List.of("SNAP-A", "SNAP-B"));
        when(repository.distinctSnapshotIds()).thenReturn(List.of("SNAP-A", "SNAP-B"));
        assertThat(reaper.reap()).isZero();
    }
}
