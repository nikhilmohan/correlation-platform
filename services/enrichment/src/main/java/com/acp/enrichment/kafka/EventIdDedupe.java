package com.acp.enrichment.kafka;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * A bounded, time-expiring recently-seen {@code eventId} set that short-circuits exact at-least-once
 * redelivery before the pipeline (design "Idempotency detail"). Dedupe is on the envelope
 * {@code eventId}; alarm-level dedup is the {@code (source, managedObjectId, eventType)} window in
 * {@link com.acp.enrichment.pipeline.DedupStep}.
 */
@Component
public class EventIdDedupe {

    private final Cache<String, Boolean> seen = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    /**
     * @param eventId the envelope id
     * @return {@code true} if this is the first time we have seen {@code eventId} (process it);
     *     {@code false} if it is a redelivery (skip)
     */
    public boolean firstSeen(String eventId) {
        if (eventId == null) {
            return true;
        }
        return seen.asMap().putIfAbsent(eventId, Boolean.TRUE) == null;
    }
}
