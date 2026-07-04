package com.acp.enrichment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Enrichment configuration bound from the environment (no hard-coded URLs/thresholds). Per-source
 * filter parameters and field mappings live ONLY in the mounted rulesets YAML — never here, never
 * in Knowledge.
 */
@ConfigurationProperties(prefix = "enrichment")
public class EnrichmentProperties {

    /** Path to the mounted per-source rulesets YAML ({@code ENRICHMENT_RULESETS_FILE}). */
    private String rulesetsFile = "/config/rulesets.yaml";
    /** Enable file-watch hot-reload of the rulesets YAML ({@code ENRICHMENT_RULESETS_RELOAD}). */
    private boolean rulesetsReload = false;
    /** Path to the writable chatter overlay file ({@code ENRICHMENT_CHATTER_OVERLAY_FILE}). */
    private String chatterOverlayFile = "/config/chatter-overlay.json";
    /** Domain passed to Trail Builder {@code getTrailsForObject} ({@code ENRICHMENT_DOMAIN}). */
    private String domain = "core-ip";

    /** Topic names (defaults match architecture.md). */
    private String historyTopic = "alarms.history";
    private String liveTopic = "alarms.live";
    private String enrichedTopic = "alarms.enriched";
    private String enrichedLiveTopic = "alarms.enriched.live";
    private String historyDlqTopic = "alarms.history.dlq";
    private String liveDlqTopic = "alarms.live.dlq";

    public String getRulesetsFile() {
        return rulesetsFile;
    }

    public void setRulesetsFile(String rulesetsFile) {
        this.rulesetsFile = rulesetsFile;
    }

    public boolean isRulesetsReload() {
        return rulesetsReload;
    }

    public void setRulesetsReload(boolean rulesetsReload) {
        this.rulesetsReload = rulesetsReload;
    }

    public String getChatterOverlayFile() {
        return chatterOverlayFile;
    }

    public void setChatterOverlayFile(String chatterOverlayFile) {
        this.chatterOverlayFile = chatterOverlayFile;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getHistoryTopic() {
        return historyTopic;
    }

    public void setHistoryTopic(String historyTopic) {
        this.historyTopic = historyTopic;
    }

    public String getLiveTopic() {
        return liveTopic;
    }

    public void setLiveTopic(String liveTopic) {
        this.liveTopic = liveTopic;
    }

    public String getEnrichedTopic() {
        return enrichedTopic;
    }

    public void setEnrichedTopic(String enrichedTopic) {
        this.enrichedTopic = enrichedTopic;
    }

    public String getEnrichedLiveTopic() {
        return enrichedLiveTopic;
    }

    public void setEnrichedLiveTopic(String enrichedLiveTopic) {
        this.enrichedLiveTopic = enrichedLiveTopic;
    }

    public String getHistoryDlqTopic() {
        return historyDlqTopic;
    }

    public void setHistoryDlqTopic(String historyDlqTopic) {
        this.historyDlqTopic = historyDlqTopic;
    }

    public String getLiveDlqTopic() {
        return liveDlqTopic;
    }

    public void setLiveDlqTopic(String liveDlqTopic) {
        this.liveDlqTopic = liveDlqTopic;
    }
}
