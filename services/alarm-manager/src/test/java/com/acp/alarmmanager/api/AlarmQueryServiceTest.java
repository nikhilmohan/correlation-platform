package com.acp.alarmmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.Fixtures;
import com.acp.alarmmanager.api.dto.AlarmDetail;
import com.acp.alarmmanager.api.dto.AlarmPage;
import com.acp.alarmmanager.domain.LifecycleState;
import com.acp.alarmmanager.domain.Role;
import com.acp.alarmmanager.domain.StateTransitionRecord;
import com.acp.alarmmanager.repository.AlarmQueryFilter;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Read-side mapping: AlarmRecord -> DTOs. AC22 — alarmType surfaced on both summary and detail,
 * distinct from eventType/probableCause. AC20 — canonical envelope total/limit/offset.
 */
class AlarmQueryServiceTest {

    private AlarmRepository alarms;
    private StateTransitionRepository transitions;
    private AlarmQueryService service;

    @BeforeEach
    void setUp() {
        alarms = Mockito.mock(AlarmRepository.class);
        transitions = Mockito.mock(StateTransitionRepository.class);
        service = new AlarmQueryService(alarms, transitions);
    }

    @Test
    void listMapsToCanonicalEnvelopeWithAlarmTypeOnSummary() {
        when(alarms.query(any())).thenReturn(List.of(
                Fixtures.alarmRecord("ALM-0001", LifecycleState.OPEN, Role.NONE, null,
                        List.of("trail-77"))));
        when(alarms.count(any())).thenReturn(5L);

        AlarmPage page = service.list(new AlarmQueryFilter(null, null, null, null, null, 2, 1));

        assertThat(page.total()).isEqualTo(5L);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).alarmType()).isEqualTo("PortDown");
        assertThat(page.items().get(0).eventType()).isEqualTo("communicationsAlarm");
    }

    @Test
    void detailMapsAlarmTypeAndOrderedTransitions() {
        when(alarms.findById("ALM-0001")).thenReturn(Optional.of(
                Fixtures.alarmRecord("ALM-0001", LifecycleState.CORRELATED, Role.ROOT_CAUSE,
                        "INC-0001", List.of("trail-77"))));
        when(transitions.findByAlarmOrdered("ALM-0001")).thenReturn(List.of(
                new StateTransitionRecord(1, "ALM-0001", "open", "ingest", null, null, "e1",
                        Instant.parse("2026-06-13T09:00:00Z")),
                new StateTransitionRecord(2, "ALM-0001", "correlated", "status-sync",
                        "correlation-engine", Instant.parse("2026-06-13T09:05:00Z"), "e2",
                        Instant.parse("2026-06-13T09:05:01Z"))));

        AlarmDetail detail = service.findById("ALM-0001").orElseThrow();

        assertThat(detail.alarmType()).isEqualTo("PortDown");
        assertThat(detail.eventType()).isEqualTo("communicationsAlarm");
        assertThat(detail.probableCause()).isEqualTo("lossOfSignal");
        assertThat(detail.lifecycleState()).isEqualTo("correlated");
        assertThat(detail.role()).isEqualTo("root-cause");
        assertThat(detail.incidentId()).isEqualTo("INC-0001");
        assertThat(detail.transitions()).hasSize(2);
        assertThat(detail.transitions().get(0).toState()).isEqualTo("open");
        assertThat(detail.transitions().get(1).toState()).isEqualTo("correlated");
        assertThat(detail.transitions().get(1).source()).isEqualTo("correlation-engine");
    }

    @Test
    void unknownIdReturnsEmpty() {
        when(alarms.findById("NOPE")).thenReturn(Optional.empty());
        assertThat(service.findById("NOPE")).isEmpty();
    }
}
