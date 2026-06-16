package com.acp.topology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Topology Service entrypoint — sole owner of the NebulaGraph topology graph.
 *
 * <p>Ingests topology snapshot files via {@code POST /topology/snapshots}, lifts them into the
 * typed multi-layer graph, mints a {@code snapshotId}, records snapshot metadata in PostgreSQL,
 * and emits {@code topology.changed}. Serves a domain-scoped, typed query API. NebulaGraph + nGQL
 * are internal implementation details, fully abstracted behind this service's API.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TopologyApplication {

    public static void main(String[] args) {
        SpringApplication.run(TopologyApplication.class, args);
    }
}
