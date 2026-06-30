package com.acp.enrichment.kafka;

import java.util.Map;

/**
 * A minimally-parsed inbound envelope: the seven envelope fields validated at envelope level, with
 * the {@code payload} kept as a raw map (NOT yet a canonical {@code AlarmEvent} — raw alarms carry
 * source-specific fields like {@code rawSeverity}/{@code ne} that are not on the canonical schema).
 * {@code NormalizeStep} turns the raw payload into the canonical form; the output is then
 * re-validated by the {@code EventCodec} on serialize (canonical-output invariant).
 *
 * @param eventId envelope id (idempotency key)
 * @param type the discriminator (must be {@code AlarmEvent} for enrichment input)
 * @param schemaVersion envelope schema version
 * @param occurredAt envelope timestamp (propagated)
 * @param source the source selector
 * @param traceId distributed trace id (propagated)
 * @param payload the raw alarm payload
 */
public record RawEnvelope(String eventId, String type, int schemaVersion, String occurredAt,
        String source, String traceId, Map<String, Object> payload) {
}
