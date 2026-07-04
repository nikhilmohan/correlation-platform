package com.acp.alarmmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * All Alarm Manager config, bound from environment (no hard-coded URLs, credentials, or
 * thresholds). Kafka topic/DLQ names, consumer group IDs, retry tuning and the query page-size
 * caps are all resolved from environment configuration via {@code application.yml}.
 */
@ConfigurationProperties(prefix = "alarm-manager")
public class AlarmManagerProperties {

    @NestedConfigurationProperty
    private final Kafka kafka = new Kafka();

    @NestedConfigurationProperty
    private final Query query = new Query();

    public Kafka getKafka() {
        return kafka;
    }

    public Query getQuery() {
        return query;
    }

    /** Kafka topic/DLQ/group/retry config. */
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String enrichedTopic = "alarms.enriched.live";
        private String statusTopic = "alarms.status.changed";
        private String correlationTopic = "correlation.results";
        private String persistedTopic = "alarms.persisted.live";
        private String enrichedDlq = "alarms.enriched.live.dlq";
        private String statusDlq = "alarms.status.changed.dlq";
        private String correlationDlq = "correlation.results.dlq";
        private String groupIdEnriched = "alarm-manager-enriched";
        private String groupIdStatus = "alarm-manager-status";
        private String groupIdCorrelation = "alarm-manager-correlation";
        private int maxRetries = 3;
        private long retryBackoffMs = 1000;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getEnrichedTopic() {
            return enrichedTopic;
        }

        public void setEnrichedTopic(String enrichedTopic) {
            this.enrichedTopic = enrichedTopic;
        }

        public String getStatusTopic() {
            return statusTopic;
        }

        public void setStatusTopic(String statusTopic) {
            this.statusTopic = statusTopic;
        }

        public String getCorrelationTopic() {
            return correlationTopic;
        }

        public void setCorrelationTopic(String correlationTopic) {
            this.correlationTopic = correlationTopic;
        }

        public String getPersistedTopic() {
            return persistedTopic;
        }

        public void setPersistedTopic(String persistedTopic) {
            this.persistedTopic = persistedTopic;
        }

        public String getEnrichedDlq() {
            return enrichedDlq;
        }

        public void setEnrichedDlq(String enrichedDlq) {
            this.enrichedDlq = enrichedDlq;
        }

        public String getStatusDlq() {
            return statusDlq;
        }

        public void setStatusDlq(String statusDlq) {
            this.statusDlq = statusDlq;
        }

        public String getCorrelationDlq() {
            return correlationDlq;
        }

        public void setCorrelationDlq(String correlationDlq) {
            this.correlationDlq = correlationDlq;
        }

        public String getGroupIdEnriched() {
            return groupIdEnriched;
        }

        public void setGroupIdEnriched(String groupIdEnriched) {
            this.groupIdEnriched = groupIdEnriched;
        }

        public String getGroupIdStatus() {
            return groupIdStatus;
        }

        public void setGroupIdStatus(String groupIdStatus) {
            this.groupIdStatus = groupIdStatus;
        }

        public String getGroupIdCorrelation() {
            return groupIdCorrelation;
        }

        public void setGroupIdCorrelation(String groupIdCorrelation) {
            this.groupIdCorrelation = groupIdCorrelation;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }
    }

    /** Query API page-size config. */
    public static class Query {
        private int maxPageSize = 500;
        private int defaultPageSize = 50;

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }
    }
}
