package com.acp.topology.config;

import com.acp.eventmodel.EventCodec;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the frozen event-model codec, the NebulaGraph pool, and a system clock. */
@Configuration
public class BeansConfig {

    private static final Logger log = LoggerFactory.getLogger(BeansConfig.class);

    /** The frozen event-model codec (serializes the canonical wire format). */
    @Bean
    public EventCodec eventCodec() {
        return new EventCodec();
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * The nebula-java connection pool (the ONLY graph client). Internal-only: the host list +
     * credentials come from config and are never forwarded to callers or logged.
     */
    @Bean(destroyMethod = "close")
    public NebulaPool nebulaPool(TopologyProperties properties) throws Exception {
        TopologyProperties.Nebula cfg = properties.getNebula();
        List<HostAddress> hosts = new ArrayList<>();
        for (String hostPort : cfg.getHosts().split(",")) {
            String trimmed = hostPort.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            String host = colon > 0 ? trimmed.substring(0, colon) : trimmed;
            int port = colon > 0 ? Integer.parseInt(trimmed.substring(colon + 1)) : 9669;
            hosts.add(new HostAddress(host, port));
        }
        NebulaPoolConfig poolConfig = new NebulaPoolConfig();
        poolConfig.setMaxConnSize(cfg.getPoolMax());
        poolConfig.setMinConnSize(cfg.getPoolMin());
        NebulaPool pool = new NebulaPool();
        boolean ok = pool.init(hosts, poolConfig);
        log.info("NebulaPool initialized (ok={}) against {} graphd host(s)", ok, hosts.size());
        return pool;
    }
}
