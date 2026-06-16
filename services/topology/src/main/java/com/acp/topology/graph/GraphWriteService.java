package com.acp.topology.graph;

import java.util.List;
import org.springframework.stereotype.Service;

/** Thin write facade over the {@link GraphRepository} port (keeps callers off nGQL). */
@Service
public class GraphWriteService {

    private final GraphRepository repository;

    public GraphWriteService(GraphRepository repository) {
        this.repository = repository;
    }

    public void writeSnapshot(List<GraphVertex> vertices, List<GraphEdge> edges) {
        repository.writeSnapshot(vertices, edges);
    }

    public void deleteSnapshot(String snapshotId) {
        repository.deleteSnapshot(snapshotId);
    }
}
