package com.acp.alarmmanager.service;

import com.acp.alarmmanager.domain.Role;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import com.acp.eventmodel.TypedEnvelope;
import com.acp.eventmodel.generated.CorrelationResultEvent;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The canonical ROLE + incident channel ({@code correlation.results}). Scoped to ROLE and incident
 * linkage ONLY — it never writes {@code lifecycle_state} (that is the STATE channel's). Marks the
 * {@code rootCauseAlarmId} alarm {@code root-cause} with the {@code incidentId} and each
 * {@code childAlarmIds} alarm {@code child} with the same {@code incidentId}, appending one
 * {@code role-assigned} audit entry per affected alarm. Idempotent on the envelope {@code eventId}.
 */
@Service
public class CorrelationService {

    /** Audit {@code to_state}/{@code reason} for a ROLE-only assignment. */
    public static final String ROLE_ASSIGNED = "role-assigned";

    private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);

    private final ProcessedEventRepository processed;
    private final AlarmRepository alarms;
    private final StateTransitionRepository transitions;
    private final AmMetrics metrics;

    public CorrelationService(ProcessedEventRepository processed, AlarmRepository alarms,
            StateTransitionRepository transitions, AmMetrics metrics) {
        this.processed = processed;
        this.alarms = alarms;
        this.transitions = transitions;
        this.metrics = metrics;
    }

    /** Apply one codec-validated {@code CorrelationResultEvent} envelope. */
    @Transactional
    public void applyRoleAndIncident(TypedEnvelope<Object> envelope) {
        Instant now = Instant.now();
        if (!processed.claim(envelope.getEventId(), now)) {
            log.debug("correlation eventId={} already applied — idempotent no-op",
                    envelope.getEventId());
            return;
        }
        CorrelationResultEvent payload = (CorrelationResultEvent) envelope.getPayload();
        String incidentId = payload.getIncidentId();
        String eventId = envelope.getEventId();

        assign(payload.getRootCauseAlarmId(), Role.ROOT_CAUSE, incidentId, eventId, now);
        List<String> children = payload.getChildAlarmIds();
        if (children != null) {
            for (String childId : children) {
                assign(childId, Role.CHILD, incidentId, eventId, now);
            }
        }
        metrics.correlationApplied();
    }

    private void assign(String alarmId, Role role, String incidentId, String eventId, Instant now) {
        if (alarmId == null) {
            return;
        }
        if (!alarms.exists(alarmId)) {
            log.info("correlation for unknown alarmId={} — recording metric, no row updated",
                    alarmId);
            metrics.correlationForUnknownAlarm();
            return;
        }
        alarms.updateRoleAndIncident(alarmId, role, incidentId, now);
        transitions.append(alarmId, ROLE_ASSIGNED, ROLE_ASSIGNED, null, null, eventId, now);
    }
}
