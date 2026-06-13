package com.acp.topology.ingest;

import com.acp.topology.graph.GraphEdge;
import com.acp.topology.graph.GraphVertex;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Lifts flat snapshot records into typed, domain-tagged graph vertices/edges (Task 2, Algorithm §A).
 * TAG-/EDGE-type selection is purely data-driven (the record's {@code objectType} / {@code relation})
 * — no {@code switch} over Core-IP semantics, so {@code Site}, {@code Interface}, {@code LOCATED_AT},
 * {@code HOSTS}, {@code TERMINATES} all lift through the same generic path with no special-casing.
 * Attributes are carried verbatim (serialized to a JSON string property inside the repository).
 */
@Service
public class LiftingService {

    /** A lifted snapshot ready to persist. */
    public record Lifted(List<GraphVertex> vertices, List<GraphEdge> edges) {
    }

    public Lifted lift(SnapshotFile file, String snapshotId) {
        List<GraphVertex> vertices = new ArrayList<>();
        for (SnapshotFile.NodeRecord n : file.nodes()) {
            vertices.add(new GraphVertex(
                    n.managedObjectId(),
                    n.objectType(),
                    file.domain(),
                    snapshotId,
                    n.name(),
                    n.attributes()));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (SnapshotFile.EdgeRecord e : file.edges()) {
            edges.add(new GraphEdge(
                    e.from(),
                    e.to(),
                    e.relation(),
                    file.domain(),
                    snapshotId,
                    e.attributes()));
        }
        return new Lifted(vertices, edges);
    }
}
