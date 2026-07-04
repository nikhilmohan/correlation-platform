package com.acp.correlationengine.integration;

import com.acp.eventmodel.TypedEnvelope;
import java.time.Instant;
import java.util.UUID;

/**
 * Small helper for building canonical event-model {@link TypedEnvelope}s for the two produced
 * event types ({@code CorrelationResultEvent}, {@code AlarmStatusChange}). {@code source} is always
 * {@code correlation-engine}; timestamps are ISO-8601 UTC ({@code Z}) strings; {@code eventId} and
 * {@code traceId} are fresh UUIDs. Schema version is the current major (1).
 */
final class EventEnvelopes {

    static final String SOURCE = "correlation-engine";
    static final int SCHEMA_VERSION = 1;

    private EventEnvelopes() {
    }

    static <P> TypedEnvelope<P> wrap(String type, P payload) {
        return new TypedEnvelope<>(
                UUID.randomUUID().toString(),
                type,
                SCHEMA_VERSION,
                Instant.now().toString(),
                SOURCE,
                UUID.randomUUID().toString(),
                payload);
    }
}
