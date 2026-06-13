package com.acp.topology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * All Topology config, bound from environment (no hard-coded URLs, credentials or thresholds).
 * NebulaGraph + PostgreSQL connection details are internal-only and never forwarded to callers.
 */
@ConfigurationProperties(prefix = "topology")
public class TopologyProperties {

    @NestedConfigurationProperty
    private final Nebula nebula = new Nebula();

    @NestedConfigurationProperty
    private final Kafka kafka = new Kafka();

    @NestedConfigurationProperty
    private final Ingest ingest = new Ingest();

    @NestedConfigurationProperty
    private final Traversal traversal = new Traversal();

    @NestedConfigurationProperty
    private final Knowledge knowledge = new Knowledge();

    @NestedConfigurationProperty
    private final Snapshot snapshot = new Snapshot();

    public Nebula getNebula() {
        return nebula;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Ingest getIngest() {
        return ingest;
    }

    public Traversal getTraversal() {
        return traversal;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    /** NebulaGraph connection (internal-only — never exposed to callers or logs). */
    public static class Nebula {
        /** Comma-separated host:port list for graphd, e.g. {@code nebula-graphd:9669}. */
        private String hosts = "localhost:9669";
        private String space = "topology";
        private String username = "root";
        private String password = "nebula";
        private int poolMax = 20;
        private int poolMin = 2;
        /** storaged host:port for the idempotent ADD HOSTS bootstrap, e.g. nebula-storaged:9779. */
        private String storagedHost = "nebula-storaged:9779";
        /** Whether NebulaSchemaBootstrap runs ADD HOSTS / CREATE SPACE on startup. */
        private boolean bootstrapOnStartup = true;

        public String getHosts() {
            return hosts;
        }

        public void setHosts(String hosts) {
            this.hosts = hosts;
        }

        public String getSpace() {
            return space;
        }

        public void setSpace(String space) {
            this.space = space;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getPoolMax() {
            return poolMax;
        }

        public void setPoolMax(int poolMax) {
            this.poolMax = poolMax;
        }

        public int getPoolMin() {
            return poolMin;
        }

        public void setPoolMin(int poolMin) {
            this.poolMin = poolMin;
        }

        public String getStoragedHost() {
            return storagedHost;
        }

        public void setStoragedHost(String storagedHost) {
            this.storagedHost = storagedHost;
        }

        public boolean isBootstrapOnStartup() {
            return bootstrapOnStartup;
        }

        public void setBootstrapOnStartup(boolean bootstrapOnStartup) {
            this.bootstrapOnStartup = bootstrapOnStartup;
        }
    }

    /** Kafka producer config (idempotent). */
    public static class Kafka {
        private String topic = "topology.changed";
        private String dlqTopic = "topology.changed.dlq";

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getDlqTopic() {
            return dlqTopic;
        }

        public void setDlqTopic(String dlqTopic) {
            this.dlqTopic = dlqTopic;
        }
    }

    /** Ingestion limits. */
    public static class Ingest {
        private long maxFileBytes = 10_485_760L;

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
    }

    /** Traversal bounds. */
    public static class Traversal {
        private int maxDepth = 8;

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }
    }

    /** Knowledge Service domain-vocabulary integration (config-switchable mock|real). */
    public static class Knowledge {
        private String baseUrl = "http://knowledge:8080";
        /** {@code mock} (stub from Knowledge OpenAPI) or {@code real}. */
        private String mode = "real";
        /** Frozen path template; {@code {domain}} substituted at call time. */
        private String vocabPath = "/domains/{domain}/vocabulary";
        private long vocabTtlSeconds = 300;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getVocabPath() {
            return vocabPath;
        }

        public void setVocabPath(String vocabPath) {
            this.vocabPath = vocabPath;
        }

        public long getVocabTtlSeconds() {
            return vocabTtlSeconds;
        }

        public void setVocabTtlSeconds(long vocabTtlSeconds) {
            this.vocabTtlSeconds = vocabTtlSeconds;
        }
    }

    /** Snapshot retention (current + previous per domain). */
    public static class Snapshot {
        private int retention = 2;

        public int getRetention() {
            return retention;
        }

        public void setRetention(int retention) {
            this.retention = retention;
        }
    }
}
