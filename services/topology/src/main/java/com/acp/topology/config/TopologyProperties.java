package com.acp.topology.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * All Topology config, bound from environment (no hard-coded URLs, credentials or thresholds).
 * NebulaGraph + PostgreSQL connection details are internal-only and never forwarded to callers.
 * {@code @Validated} so a missing {@code (required)} env var (e.g. the Knowledge base URL) fails
 * fast at startup rather than starting with a silent default.
 */
@Validated
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

    @Valid
    @NestedConfigurationProperty
    private final Knowledge knowledge = new Knowledge();

    @NestedConfigurationProperty
    private final Snapshot snapshot = new Snapshot();

    @NestedConfigurationProperty
    private final Startup startup = new Startup();

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

    public Startup getStartup() {
        return startup;
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
        /**
         * Backoff interval for the {@code SHOW HOSTS}-ONLINE and {@code USE space}-usable polls
         * (S2/S5). {@code TOPOLOGY_NEBULA_POLL_INTERVAL_MS}.
         */
        private long pollIntervalMs = 1000;
        /**
         * Max wait for the configured storaged host to reach {@code Status ONLINE} before
         * {@code CREATE SPACE} (CRIT-1 / S1). {@code TOPOLOGY_NEBULA_STORAGED_ONLINE_DEADLINE_MS}.
         */
        private long storagedOnlineDeadlineMs = 60_000;
        /**
         * Max wait for {@code USE space} to succeed after {@code CREATE SPACE} (space-propagation
         * window). {@code TOPOLOGY_NEBULA_SPACE_USABLE_DEADLINE_MS}.
         */
        private long spaceUsableDeadlineMs = 60_000;
        /**
         * Backoff between background bootstrap re-attempts on a transient failure (capped by the
         * overall startup deadline; S2/S3). {@code TOPOLOGY_NEBULA_RETRY_BACKOFF_MS}.
         */
        private long retryBackoffMs = 5_000;

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

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getStoragedOnlineDeadlineMs() {
            return storagedOnlineDeadlineMs;
        }

        public void setStoragedOnlineDeadlineMs(long storagedOnlineDeadlineMs) {
            this.storagedOnlineDeadlineMs = storagedOnlineDeadlineMs;
        }

        public long getSpaceUsableDeadlineMs() {
            return spaceUsableDeadlineMs;
        }

        public void setSpaceUsableDeadlineMs(long spaceUsableDeadlineMs) {
            this.spaceUsableDeadlineMs = spaceUsableDeadlineMs;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
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
        /**
         * Generous runaway backstop for bounded traversal (#214). Topology HONOURS the
         * caller-requested depth up to this cap; it does not impose a build-policy opinion —
         * traversal depth is the trail-builder's concern (trail-builder requests 12). Raised
         * 8 → 32; env-configurable via {@code TOPOLOGY_TRAVERSAL_MAX_DEPTH}.
         */
        private int maxDepth = 32;

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }
    }

    /** Knowledge Service domain-vocabulary integration (config-switchable mock|real). */
    public static class Knowledge {
        /**
         * Knowledge Service base URL (domain-vocabulary API). REQUIRED — bound from
         * {@code TOPOLOGY_KNOWLEDGE_BASE_URL} with NO code-level default; startup fails fast if
         * unset (design config table marks this var "(required)"). No compose-hostname default is
         * baked in so the wiring is explicit per environment.
         */
        @NotBlank(message = "TOPOLOGY_KNOWLEDGE_BASE_URL is required (no default)")
        private String baseUrl;
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

    /**
     * Self-healing startup bootstrap window (Startup-Robustness Standard S2/S3). All config from env.
     */
    public static class Startup {
        /**
         * Overall self-healing startup deadline — bounds the predictable window. After this elapses,
         * background bootstrap retries stop and readiness stays DOWN (no unbounded loop; S2/S3).
         * {@code TOPOLOGY_STARTUP_DEADLINE_MS}. Default 180000ms (hard deadline; 120s target).
         */
        private long deadlineMs = 180_000;

        public long getDeadlineMs() {
            return deadlineMs;
        }

        public void setDeadlineMs(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }
    }
}
