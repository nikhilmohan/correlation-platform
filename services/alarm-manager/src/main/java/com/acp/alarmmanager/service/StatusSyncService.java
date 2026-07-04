package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.AlarmStatusChange;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The canonical STATE channel ({@code alarms.status.changed}). Applies {@code newStatus} to the
 * referenced alarm's lifecycle STATE via {@link LifecycleService} (the single state-machine
 * owner), recording {@code source}/{@code changedAt} on the audit entry. Idempotent on the
 * envelope {@code eventId} (shared {@code processed_event} guard). ROLE + {@code incidentId} are
 * never touched here — those come from {@code correlation.results}.
 */
@Service
public class StatusSyncService {

    private static final Logger log = LoggerFactory.getLogger(StatusSyncService.class);

    private final ProcessedEventRepository processed;
    private final LifecycleService lifecycle;
    private final AmMetrics metrics;

    public StatusSyncService(ProcessedEventRepository processed, LifecycleService lifecycle,
            AmMetrics metrics) {
        this.processed = processed;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
    }

    /** Apply one codec-validated {@code AlarmStatusChange} envelope. */
    @Transactional
    public void apply(TypedEnvelope<Object> envelope) {
        Instant now = Instant.now();
        if (!processed.claim(envelope.getEventId(), now)) {
            log.debug("status eventId={} already applied — idempotent no-op", envelope.getEventId());
            return;
        }
        AlarmStatusChange payload = (AlarmStatusChange) envelope.getPayload();
        String alarmId = payload.getAlarmId();
        String source = payload.getSource();
        Instant changedAt = parseInstant(payload.getChangedAt());
        String eventId = envelope.getEventId();
        AlarmStatusChange.NewStatus newStatus = payload.getNewStatus();

        switch (newStatus) {
            case OPEN -> lifecycle.applyState(alarmId, LifecycleState.OPEN, source, changedAt,
                    eventId, now);
            case IN_PROGRESS -> lifecycle.applyState(alarmId, LifecycleState.IN_PROGRESS, source,
                    changedAt, eventId, now);
            case CORRELATED -> lifecycle.applyState(alarmId, LifecycleState.CORRELATED, source,
                    changedAt, eventId, now);
            case CLEARED -> lifecycle.clear(alarmId, source, changedAt, eventId, now);
            case REVERTED_OPEN -> lifecycle.revertToOpen(alarmId, source, changedAt, eventId, now);
            default -> throw new IllegalStateException("unhandled newStatus: " + newStatus);
        }
        metrics.statusApplied(newStatus.value());
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
