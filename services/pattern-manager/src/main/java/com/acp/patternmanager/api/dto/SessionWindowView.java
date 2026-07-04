package com.acp.patternmanager.api.dto;

/**
 * The derived session-window rule as served by the read API — {@code {windowMs, type}}. Read-only
 * in MVP.
 *
 * @param windowMs session-window duration in ms (> 0)
 * @param type {@code gap-based} or {@code fixed}
 */
public record SessionWindowView(long windowMs, String type) {
}
