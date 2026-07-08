package com.acp.alarmmanager.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Shared idempotency guard ({@code live_alarm.processed_event}) for the two event-driven channels
 * ({@code alarms.status.changed} and {@code correlation.results}), keyed on the envelope
 * {@code eventId}. A unique first-insert marks the event as processed-once.
 */
@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempt to claim {@code eventId} as newly-processed.
     *
     * @return {@code true} iff this call inserted the row (the event is new and should be applied);
     *     {@code false} if it was already present (Kafka redelivery — no-op).
     */
    public boolean claim(String eventId, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO live_alarm.processed_event (event_id, applied_at) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, Timestamp.from(now));
        return inserted == 1;
    }

    /**
     * Demo/ops reset: delete every row from the {@code live_alarm.processed_event} dedup ledger and
     * return the number of rows deleted. Clearing the ledger lets a fresh demo run re-ingest the
     * same {@code eventId}s cleanly. No FK dependency.
     */
    public int deleteAll() {
        return jdbc.update("DELETE FROM live_alarm.processed_event");
    }
}
