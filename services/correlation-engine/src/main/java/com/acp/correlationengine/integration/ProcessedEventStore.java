package com.acp.correlationengine.integration;

/**
 * Idempotency ledger for consumed events deduped on {@code eventId} ({@code patterns.approved} /
 * {@code codebook.generated}). {@link #markIfNew} returns true only the first time an
 * {@code eventId} is seen for a scope, so a redelivered event is a no-op (at-least-once delivery).
 */
public interface ProcessedEventStore {

    /**
     * @param scope the topic/scope discriminator
     * @param eventId the envelope {@code eventId}
     * @return true if this {@code eventId} was not previously recorded for {@code scope} (process it);
     *     false if already seen (skip — idempotent)
     */
    boolean markIfNew(String scope, String eventId);
}
