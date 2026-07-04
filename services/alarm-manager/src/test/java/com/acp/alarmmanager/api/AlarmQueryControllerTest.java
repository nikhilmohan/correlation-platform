package com.acp.alarmmanager.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.alarmmanager.api.dto.AlarmDetail;
import com.acp.alarmmanager.api.dto.AlarmPage;
import com.acp.alarmmanager.api.dto.AlarmSummary;
import com.acp.alarmmanager.api.dto.TransitionDto;
import com.acp.alarmmanager.config.AlarmManagerProperties;
import com.acp.alarmmanager.repository.AlarmQueryFilter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AC8/AC19 — state filter. AC9 — trailId filter. AC10 — incidentId filter. AC11 — time window.
 * AC6 — detail returns correlated/root-cause/incidentId + open&correlated transitions with
 * distinct timestamps. AC20/AC21 (P3-G3) — canonical { items, total, limit, offset } envelope
 * with limit/offset params. AC22 — alarmType on summary + detail, distinct from
 * eventType/probableCause. Plus 400 (bad state / from>to) and 404 (unknown id).
 *
 * <p>Standalone MockMvc (no application context) so the web contract is exercised in isolation
 * with a mocked {@link AlarmQueryService}.
 */
class AlarmQueryControllerTest {

    private MockMvc mvc;
    private AlarmQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = Mockito.mock(AlarmQueryService.class);
        AlarmManagerProperties props = new AlarmManagerProperties();
        AlarmQueryController controller = new AlarmQueryController(queryService, props);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AlarmSummary summary(String alarmId, String state, String role, String incidentId,
            List<String> trailIds) {
        return new AlarmSummary(alarmId, "Port:ne1-1-1", "communicationsAlarm", "PortDown",
                "critical", "2026-06-13T09:00:00Z", state, role, incidentId, trailIds);
    }

    // --- AC20/AC21: canonical envelope + filter passthrough ---
    @Test
    void returnsCanonicalItemsTotalLimitOffsetEnvelopeWithLimitOffsetParams() throws Exception {
        when(queryService.list(any())).thenReturn(new AlarmPage(
                List.of(summary("ALM-0001", "open", "none", null, List.of("trail-77"))), 3, 2, 1));

        mvc.perform(get("/alarms").param("limit", "2").param("offset", "1"))
                .andExpect(status().isOk())
                // JSON object with exactly the canonical keys — NOT a bare array, NOT Spring Page.
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist())
                // AC22: alarmType present + distinct from eventType.
                .andExpect(jsonPath("$.items[0].alarmType").value("PortDown"))
                .andExpect(jsonPath("$.items[0].eventType").value("communicationsAlarm"));

        ArgumentCaptor<AlarmQueryFilter> f = ArgumentCaptor.forClass(AlarmQueryFilter.class);
        org.mockito.Mockito.verify(queryService).list(f.capture());
        org.assertj.core.api.Assertions.assertThat(f.getValue().limit()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(f.getValue().offset()).isEqualTo(1);
    }

    // --- AC8: state=open passthrough ---
    @Test
    void filtersByLifecycleStateOpen() throws Exception {
        when(queryService.list(any())).thenReturn(new AlarmPage(List.of(), 0, 50, 0));

        mvc.perform(get("/alarms").param("state", "open")).andExpect(status().isOk());

        ArgumentCaptor<AlarmQueryFilter> f = ArgumentCaptor.forClass(AlarmQueryFilter.class);
        org.mockito.Mockito.verify(queryService).list(f.capture());
        org.assertj.core.api.Assertions.assertThat(f.getValue().state().wire()).isEqualTo("open");
    }

    // --- AC19: state=in-progress passthrough ---
    @Test
    void filtersByLifecycleStateInProgress() throws Exception {
        when(queryService.list(any())).thenReturn(new AlarmPage(List.of(), 0, 50, 0));

        mvc.perform(get("/alarms").param("state", "in-progress")).andExpect(status().isOk());

        ArgumentCaptor<AlarmQueryFilter> f = ArgumentCaptor.forClass(AlarmQueryFilter.class);
        org.mockito.Mockito.verify(queryService).list(f.capture());
        org.assertj.core.api.Assertions.assertThat(f.getValue().state().wire())
                .isEqualTo("in-progress");
    }

    // --- AC9/AC10/AC11: trailId / incidentId / time-window passthrough ---
    @Test
    void filtersByTrailIdIncidentIdAndTimeWindow() throws Exception {
        when(queryService.list(any())).thenReturn(new AlarmPage(List.of(), 0, 50, 0));

        mvc.perform(get("/alarms")
                        .param("trailId", "trail-77")
                        .param("incidentId", "INC-0001")
                        .param("from", "2026-06-13T08:00:00Z")
                        .param("to", "2026-06-13T10:00:00Z"))
                .andExpect(status().isOk());

        ArgumentCaptor<AlarmQueryFilter> f = ArgumentCaptor.forClass(AlarmQueryFilter.class);
        org.mockito.Mockito.verify(queryService).list(f.capture());
        AlarmQueryFilter filter = f.getValue();
        org.assertj.core.api.Assertions.assertThat(filter.trailId()).isEqualTo("trail-77");
        org.assertj.core.api.Assertions.assertThat(filter.incidentId()).isEqualTo("INC-0001");
        org.assertj.core.api.Assertions.assertThat(filter.from()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(filter.to()).isNotNull();
    }

    // --- AC6/AC22: detail record ---
    @Test
    void returnsCorrelatedRootCauseWithAlarmTypeAndOpenAndCorrelatedTransitions() throws Exception {
        AlarmDetail detail = new AlarmDetail("ALM-0001", "Port:ne1-1-1", "communicationsAlarm",
                "lossOfSignal", "PortDown", "critical", "2026-06-13T09:00:00Z", null, "raised",
                List.of("trail-77"), "correlated", "root-cause", "INC-0001",
                List.of(
                        new TransitionDto("open", "ingest", null, null, "2026-06-13T09:00:00Z"),
                        new TransitionDto("correlated", "status-sync", "correlation-engine",
                                "2026-06-13T09:05:00Z", "2026-06-13T09:05:01Z")));
        when(queryService.findById("ALM-0001")).thenReturn(Optional.of(detail));

        mvc.perform(get("/alarms/ALM-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("correlated"))
                .andExpect(jsonPath("$.role").value("root-cause"))
                .andExpect(jsonPath("$.incidentId").value("INC-0001"))
                .andExpect(jsonPath("$.alarmType").value("PortDown"))
                .andExpect(jsonPath("$.eventType").value("communicationsAlarm"))
                .andExpect(jsonPath("$.probableCause").value("lossOfSignal"))
                .andExpect(jsonPath("$.transitions[0].toState").value("open"))
                .andExpect(jsonPath("$.transitions[0].occurredAt").value("2026-06-13T09:00:00Z"))
                .andExpect(jsonPath("$.transitions[1].toState").value("correlated"))
                .andExpect(jsonPath("$.transitions[1].occurredAt").value("2026-06-13T09:05:01Z"));
    }

    @Test
    void unknownAlarmIdReturns404() throws Exception {
        when(queryService.findById("NOPE")).thenReturn(Optional.empty());

        mvc.perform(get("/alarms/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidStateEnumReturns400() throws Exception {
        mvc.perform(get("/alarms").param("state", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void fromAfterToReturns400() throws Exception {
        mvc.perform(get("/alarms")
                        .param("from", "2026-06-13T10:00:00Z")
                        .param("to", "2026-06-13T08:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
