package com.acp.correlationengine.topology;

import java.util.Optional;

/**
 * Config-switchable client for the ONE Topology Service read the Correlation Engine needs at
 * startup: discovering the current topology snapshot so the compatibility index can be built
 * immediately, without waiting for a live {@code trails.built} event to arrive (which, in a
 * long-running system, was already consumed and committed long before CE restarted).
 *
 * <p>Built + mocked against Topology's published {@code openapi.json}
 * ({@code GET /topology/snapshots} -> {@code {snapshots:[{snapshotId,status,domain,...}]}}). Same
 * code path in {@code mock} (stub generated from the OpenAPI) and {@code real} (compose
 * {@code topology}) modes — only the base URL differs. CE never touches the graph store; this is
 * an API read only (single-owner rule preserved — Topology owns the graph).
 */
public interface TopologyClient {

    /**
     * @return the {@code snapshotId} of the entry whose {@code status == "current"} for the given
     *     {@code domain}, or {@link Optional#empty()} if none is current or Topology is unreachable.
     */
    Optional<String> currentSnapshotId(String domain);
}
