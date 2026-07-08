package com.acp.correlationengine.api.dto;

/**
 * Response body for {@code POST /admin/reset-correlation} (the P3 demo/ops reset). Reports how many
 * rows were purged from the CE-owned incident tables and confirms the in-memory correlation session
 * was reset. Idempotent: a second reset with nothing to purge returns zeros and {@code resetInMemory=true}.
 *
 * @param purgedIncidents rows removed from {@code incident.incident}
 * @param purgedIncidentAlarms rows removed from {@code incident.incident_alarm}
 * @param resetInMemory always {@code true} — the in-memory session state was cleared
 */
public record ResetResult(long purgedIncidents, long purgedIncidentAlarms, boolean resetInMemory) {
}
