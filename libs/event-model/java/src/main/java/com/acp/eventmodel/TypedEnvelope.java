package com.acp.eventmodel;

import java.util.Objects;

/**
 * Envelope with its payload resolved to a concrete typed POJO.
 *
 * <p>Mirrors the envelope's seven wire fields exactly; {@code payload} holds the typed payload
 * instance (one of the nine generated payload classes) rather than a raw map. This is the Java
 * counterpart of the Python {@code TypedEnvelope}. Construct directly for serialization, or via
 * {@link EventCodec#deserialize}.
 *
 * @param <P> the payload type
 */
public final class TypedEnvelope<P> {

    private final String eventId;
    private final String type;
    private final int schemaVersion;
    private final String occurredAt;
    private final String source;
    private final String traceId;
    private final P payload;

    public TypedEnvelope(String eventId, String type, int schemaVersion, String occurredAt,
            String source, String traceId, P payload) {
        this.eventId = eventId;
        this.type = type;
        this.schemaVersion = schemaVersion;
        this.occurredAt = occurredAt;
        this.source = source;
        this.traceId = traceId;
        this.payload = payload;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    /** @return the {@code occurredAt} timestamp as an ISO-8601 UTC ({@code Z}) string. */
    public String getOccurredAt() {
        return occurredAt;
    }

    public String getSource() {
        return source;
    }

    public String getTraceId() {
        return traceId;
    }

    public P getPayload() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypedEnvelope<?> other)) {
            return false;
        }
        return schemaVersion == other.schemaVersion
                && Objects.equals(eventId, other.eventId)
                && Objects.equals(type, other.type)
                && Objects.equals(occurredAt, other.occurredAt)
                && Objects.equals(source, other.source)
                && Objects.equals(traceId, other.traceId)
                && Objects.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, type, schemaVersion, occurredAt, source, traceId, payload);
    }

    @Override
    public String toString() {
        return "TypedEnvelope{eventId=" + eventId + ", type=" + type + ", schemaVersion="
                + schemaVersion + ", occurredAt=" + occurredAt + ", source=" + source + ", traceId="
                + traceId + ", payload=" + payload + '}';
    }
}
