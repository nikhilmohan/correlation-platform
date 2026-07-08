package com.acp.alarmmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.acp.alarmmanager.api.dto.PurgeSummary;
import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.PendingStatusRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * P3 live-state purge (demo/ops reset). Verifies the transactional service:
 * <ul>
 *   <li>deletes all rows from the four {@code live_alarm} tables and returns the correct counts;</li>
 *   <li>deletes in FK-safe order (state_transition child BEFORE the alarm parent);</li>
 *   <li>is idempotent — a second purge on an empty store returns all zeros;</li>
 *   <li>increments {@code live_alarms_purged_total} by the number of alarms purged;</li>
 *   <li>references ONLY the four live_alarm repos — no other-schema store is touched.</li>
 * </ul>
 */
class PurgeServiceTest {

    private final StateTransitionRepository transitions = Mockito.mock(StateTransitionRepository.class);
    private final PendingStatusRepository pendingStatus = Mockito.mock(PendingStatusRepository.class);
    private final ProcessedEventRepository processedEvents = Mockito.mock(ProcessedEventRepository.class);
    private final AlarmRepository alarms = Mockito.mock(AlarmRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AmMetrics metrics = new AmMetrics(registry);

    private final PurgeService service =
            new PurgeService(transitions, pendingStatus, processedEvents, alarms, metrics);

    @Test
    void purgesAllFourTablesAndReturnsCounts() {
        when(transitions.deleteAll()).thenReturn(7);
        when(pendingStatus.deleteAll()).thenReturn(2);
        when(processedEvents.deleteAll()).thenReturn(5);
        when(alarms.deleteAll()).thenReturn(3);

        PurgeSummary summary = service.purgeLiveAlarms();

        assertThat(summary.purgedAlarms()).isEqualTo(3);
        assertThat(summary.purgedTransitions()).isEqualTo(7);
        assertThat(summary.purgedPendingStatus()).isEqualTo(2);
        assertThat(summary.purgedProcessedEvents()).isEqualTo(5);
    }

    @Test
    void deletesChildTransitionsBeforeParentAlarmForFkSafety() {
        when(transitions.deleteAll()).thenReturn(1);
        when(pendingStatus.deleteAll()).thenReturn(0);
        when(processedEvents.deleteAll()).thenReturn(0);
        when(alarms.deleteAll()).thenReturn(1);

        service.purgeLiveAlarms();

        // FK: state_transition -> alarm; the child MUST be deleted before the parent.
        InOrder order = inOrder(transitions, alarms);
        order.verify(transitions).deleteAll();
        order.verify(alarms).deleteAll();
    }

    @Test
    void incrementsPurgedMetricByAlarmCount() {
        when(transitions.deleteAll()).thenReturn(0);
        when(pendingStatus.deleteAll()).thenReturn(0);
        when(processedEvents.deleteAll()).thenReturn(0);
        when(alarms.deleteAll()).thenReturn(4);

        service.purgeLiveAlarms();

        assertThat(registry.counter("live_alarms_purged_total").count()).isEqualTo(4.0);
    }

    @Test
    void isIdempotentReturningZerosOnEmptyStore() {
        when(transitions.deleteAll()).thenReturn(0);
        when(pendingStatus.deleteAll()).thenReturn(0);
        when(processedEvents.deleteAll()).thenReturn(0);
        when(alarms.deleteAll()).thenReturn(0);

        PurgeSummary summary = service.purgeLiveAlarms();

        assertThat(summary.purgedAlarms()).isZero();
        assertThat(summary.purgedTransitions()).isZero();
        assertThat(summary.purgedPendingStatus()).isZero();
        assertThat(summary.purgedProcessedEvents()).isZero();
        assertThat(registry.counter("live_alarms_purged_total").count()).isZero();
    }
}
