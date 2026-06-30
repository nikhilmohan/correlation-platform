package com.acp.enrichment.pipeline;

/**
 * The windowed-state key: {@code (path, source, managedObjectId, eventType)}. Including
 * {@code path} and {@code source} keeps history/live state and per-source windows independent so
 * per-source filter parameters apply without cross-contamination (criterion 11; design
 * "Windowed-state key").
 */
public record WindowKey(Path path, String source, String managedObjectId, String eventType) {
}
