package com.acp.topology.ingest;

import java.util.List;
import java.util.Map;

/**
 * The parsed topology snapshot file (validated against snapshot.schema.json + semantic checks +
 * domain vocabulary). Internal model — never exposed to NebulaGraph or to callers directly.
 */
public record SnapshotFile(
        int schemaVersion,
        String snapshotId,
        String domain,
        List<NodeRecord> nodes,
        List<EdgeRecord> edges) {

    /** A flat node record from the file. */
    public record NodeRecord(
            String managedObjectId,
            String objectType,
            String name,
            Map<String, Object> attributes) {
    }

    /** A flat edge record from the file. */
    public record EdgeRecord(
            String from,
            String to,
            String relation,
            Map<String, Object> attributes) {
    }
}
