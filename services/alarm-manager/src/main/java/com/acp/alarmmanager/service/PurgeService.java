package com.acp.alarmmanager.service;

import com.acp.alarmmanager.api.dto.PurgeSummary;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.PendingStatusRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo/ops reset of the P3 live-alarm path. Deletes ALL rows from the Alarm Manager's OWN
 * {@code live_alarm} schema so the web-ui topology (which colours sites/nodes by active alarm
 * severity from {@code GET /alarms}) returns to all-green.
 *
 * <p><strong>Single-owner scope (golden rule):</strong> this purge references ONLY the four
 * {@code live_alarm} repositories the Alarm Manager owns. It does NOT touch any other schema
 * ({@code noise_filter}, {@code pattern}, {@code codebook}, {@code knowledge}, {@code incident}) —
 * P1 topology and P2 data are untouched; the Correlation Engine purges {@code incident} separately.
 *
 * <p><strong>FK-safe order:</strong> {@code live_alarm.state_transition} references
 * {@code live_alarm.alarm} (child -> parent). The purge deletes the child audit rows FIRST, then
 * the FK-free {@code pending_status} and {@code processed_event}, then the parent {@code alarm}
 * table, all inside ONE transaction so a failure rolls the whole reset back (no partial purge).
 *
 * <p><strong>Idempotent:</strong> a second call on an already-empty store deletes nothing and
 * returns all-zero counts.
 */
@Service
public class PurgeService {

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

    private final StateTransitionRepository transitions;
    private final PendingStatusRepository pendingStatus;
    private final ProcessedEventRepository processedEvents;
    private final AlarmRepository alarms;
    private final AmMetrics metrics;

    public PurgeService(StateTransitionRepository transitions,
            PendingStatusRepository pendingStatus,
            ProcessedEventRepository processedEvents,
            AlarmRepository alarms,
            AmMetrics metrics) {
        this.transitions = transitions;
        this.pendingStatus = pendingStatus;
        this.processedEvents = processedEvents;
        this.alarms = alarms;
        this.metrics = metrics;
    }

    /**
     * Transactionally delete every row from the four {@code live_alarm} tables in FK-safe order and
     * return the deleted counts. Increments {@code live_alarms_purged_total} by the number of
     * alarms purged.
     */
    @Transactional
    public PurgeSummary purgeLiveAlarms() {
        // FK-safe order: child audit rows first, then FK-free ledgers, then the parent alarm table.
        int purgedTransitions = transitions.deleteAll();
        int purgedPendingStatus = pendingStatus.deleteAll();
        int purgedProcessedEvents = processedEvents.deleteAll();
        int purgedAlarms = alarms.deleteAll();

        metrics.alarmsPurged(purgedAlarms);
        log.info("Purged P3 live-alarm state: alarms={}, transitions={}, pendingStatus={}, "
                + "processedEvents={}", purgedAlarms, purgedTransitions, purgedPendingStatus,
                purgedProcessedEvents);

        return new PurgeSummary(purgedAlarms, purgedTransitions, purgedPendingStatus,
                purgedProcessedEvents);
    }
}
