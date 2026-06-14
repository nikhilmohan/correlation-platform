package com.acp.topology.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.NodeListDto;
import com.acp.topology.api.dto.SnapshotSummaryDto;
import com.acp.topology.api.dto.TraversalDto;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.GraphReadService;
import com.acp.topology.meta.SnapshotMetadataService;
import com.acp.topology.meta.SnapshotRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * AC-12 (resolve + 404), AC-13 (list-by-type), AC-14 (snapshot listing current+previous), plus the
 * query-layer rules: domain inference, current/previous resolution, traversal depth-bound (AC-11
 * validation). GraphReadService + SnapshotMetadataService mocked.
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private GraphReadService graph;

    @Mock
    private SnapshotMetadataService metadata;

    private QueryService service;

    @BeforeEach
    void setUp() {
        service = new QueryService(graph, metadata, new TopologyProperties()); // maxDepth = 32
    }

    private void currentSnapshotIs(String domain, String snapshotId) {
        when(metadata.findCurrent(domain)).thenReturn(Optional.of(record(snapshotId, domain,
                "current")));
    }

    @Test
    void getNodeResolvesCurrentSnapshotAndReturnsNode() {
        currentSnapshotIs("core-ip", "SNAP-1");
        when(graph.getNode("Node:PE1", "core-ip", "SNAP-1")).thenReturn(Optional.of(
                new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1", Map.of())));

        NodeDto node = service.getNode("Node:PE1", "core-ip", null);
        assertThat(node.objectType()).isEqualTo("Node");
    }

    @Test
    void getNodeThrows404WhenUnknown() {
        currentSnapshotIs("core-ip", "SNAP-1");
        when(graph.getNode("Node:NONE", "core-ip", "SNAP-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getNode("Node:NONE", "core-ip", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void listNodesByTypeScopesToCurrentSnapshot() {
        currentSnapshotIs("core-ip", "SNAP-1");
        when(graph.listNodes("Port", "core-ip", "SNAP-1")).thenReturn(List.of(
                new NodeDto("Port:p1", "Port", "core-ip", "SNAP-1", "p1", Map.of())));
        NodeListDto list = service.listNodes("Port", "core-ip", null);
        assertThat(list.count()).isEqualTo(1);
        assertThat(list.objectType()).isEqualTo("Port");
    }

    @Test
    void traversalRejectsDepthOutOfBounds() {
        assertThatThrownBy(() -> service.traverse("Node:PE1", List.of("RIDES_ON"), 0, "core-ip",
                null, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.traverse("Node:PE1", List.of("RIDES_ON"), 99, "core-ip",
                null, false)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void traversalHonoursHigherDepthUpToCap() {
        // #214: trail-builder requests depth 12 — must be accepted now that the cap is 32 (was 8).
        currentSnapshotIs("core-ip", "SNAP-1");
        when(graph.getNode("Node:PE1", "core-ip", "SNAP-1")).thenReturn(Optional.of(
                new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1", Map.of())));
        when(graph.traverse(eq("Node:PE1"), any(), anyInt(), eq("core-ip"), eq("SNAP-1"),
                anyBoolean())).thenReturn(List.of());

        TraversalDto t = service.traverse("Node:PE1", List.of("RIDES_ON"), 12, "core-ip", null,
                false);
        assertThat(t.maxDepth()).isEqualTo(12);
    }

    @Test
    void traversalRejectsDepthAboveCapWithBoundedMessage() {
        // #214: above the (raised, configurable) cap of 32 → 400 with the "[1..32]" message.
        assertThatThrownBy(() -> service.traverse("Node:PE1", List.of("RIDES_ON"), 33, "core-ip",
                null, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("[1..32]");
    }

    @Test
    void traversalRejectsMissingStartOrRelation() {
        assertThatThrownBy(() -> service.traverse(" ", List.of("RIDES_ON"), 2, "core-ip", null,
                false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.traverse("Node:PE1", List.of(), 2, "core-ip", null,
                false)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void traversalReturnsReachedWithinBound() {
        currentSnapshotIs("core-ip", "SNAP-1");
        when(graph.getNode("Node:PE1", "core-ip", "SNAP-1")).thenReturn(Optional.of(
                new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1", Map.of())));
        when(graph.traverse(eq("Node:PE1"), any(), anyInt(), eq("core-ip"), eq("SNAP-1"),
                anyBoolean())).thenReturn(List.of(
                        new NodeDto("IPLink:L1", "IPLink", "core-ip", "SNAP-1", "L1", Map.of())));

        TraversalDto t = service.traverse("Node:PE1", List.of("RIDES_ON"), 3, "core-ip", null,
                false);
        assertThat(t.reached()).extracting(NodeDto::managedObjectId).containsExactly("IPLink:L1");
        assertThat(t.maxDepth()).isEqualTo(3);
    }

    @Test
    void resolvesPreviousSnapshotRef() {
        when(metadata.listByDomain("core-ip")).thenReturn(List.of(
                record("SNAP-2", "core-ip", "current"), record("SNAP-1", "core-ip", "previous")));
        when(graph.getNode("Node:PE1", "core-ip", "SNAP-1")).thenReturn(Optional.of(
                new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1", Map.of())));
        NodeDto node = service.getNode("Node:PE1", "core-ip", "previous");
        assertThat(node.snapshotId()).isEqualTo("SNAP-1");
    }

    @Test
    void listSnapshotsReturnsAllForDomain() {
        when(metadata.listByDomain("core-ip")).thenReturn(List.of(
                record("SNAP-2", "core-ip", "current"), record("SNAP-1", "core-ip", "previous")));
        var list = service.listSnapshots("core-ip");
        assertThat(list.snapshots()).extracting(SnapshotSummaryDto::snapshotId)
                .containsExactly("SNAP-2", "SNAP-1");
    }

    @Test
    void currentSnapshot404WhenNone() {
        when(metadata.findCurrent("core-ip")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentSnapshot("core-ip"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void infersDomainFromAnyCurrentWhenNotSupplied() {
        when(metadata.findCurrentAnyDomain()).thenReturn(Optional.of(record("SNAP-1", "core-ip",
                "current")));
        when(metadata.findCurrent("core-ip")).thenReturn(Optional.of(record("SNAP-1", "core-ip",
                "current")));
        when(graph.listNodes(null, "core-ip", "SNAP-1")).thenReturn(List.of());
        NodeListDto list = service.listNodes(null, null, null);
        assertThat(list.domain()).isEqualTo("core-ip");
    }

    private static SnapshotRecord record(String id, String domain, String status) {
        return new SnapshotRecord(id, "full-load", domain, 1, 2, 1, status, null, Instant.now(),
                null, "t");
    }
}
