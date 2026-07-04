package com.acp.alarmmanager.api;

import com.acp.alarmmanager.api.dto.AlarmDetail;
import com.acp.alarmmanager.api.dto.AlarmPage;
import com.acp.alarmmanager.api.dto.AlarmSummary;
import com.acp.alarmmanager.api.dto.TransitionDto;
import com.acp.alarmmanager.domain.AlarmRecord;
import com.acp.alarmmanager.domain.StateTransitionRecord;
import com.acp.alarmmanager.repository.AlarmQueryFilter;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Read side of the live alarm query API. Maps store records to the frozen response DTOs. */
@Service
public class AlarmQueryService {

    private final AlarmRepository alarms;
    private final StateTransitionRepository transitions;

    public AlarmQueryService(AlarmRepository alarms, StateTransitionRepository transitions) {
        this.alarms = alarms;
        this.transitions = transitions;
    }

    /** Filtered, paged list returned as the canonical {@code { items, total, limit, offset }}. */
    public AlarmPage list(AlarmQueryFilter filter) {
        List<AlarmSummary> items = alarms.query(filter).stream().map(AlarmQueryService::toSummary)
                .toList();
        long total = alarms.count(filter);
        return new AlarmPage(items, total, filter.limit(), filter.offset());
    }

    /** Single alarm full record with ordered transition history, or empty when unknown. */
    public Optional<AlarmDetail> findById(String alarmId) {
        return alarms.findById(alarmId).map(record -> {
            List<TransitionDto> history = transitions.findByAlarmOrdered(alarmId).stream()
                    .map(AlarmQueryService::toTransition).toList();
            return toDetail(record, history);
        });
    }

    private static AlarmSummary toSummary(AlarmRecord r) {
        return new AlarmSummary(
                r.alarmId(),
                r.managedObjectId(),
                r.eventType(),
                r.alarmType(),
                r.perceivedSeverity(),
                iso(r.raisedAt()),
                r.lifecycleState().wire(),
                r.role().wire(),
                r.incidentId(),
                r.trailIds());
    }

    private static AlarmDetail toDetail(AlarmRecord r, List<TransitionDto> transitions) {
        return new AlarmDetail(
                r.alarmId(),
                r.managedObjectId(),
                r.eventType(),
                r.probableCause(),
                r.alarmType(),
                r.perceivedSeverity(),
                iso(r.raisedAt()),
                iso(r.clearedAt()),
                r.wireState(),
                r.trailIds(),
                r.lifecycleState().wire(),
                r.role().wire(),
                r.incidentId(),
                transitions);
    }

    private static TransitionDto toTransition(StateTransitionRecord t) {
        return new TransitionDto(
                t.toState(),
                t.reason(),
                t.source(),
                iso(t.changedAt()),
                iso(t.occurredAt()));
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
