package com.acp.alarmmanager.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.alarmmanager.api.dto.PurgeSummary;
import com.acp.alarmmanager.service.PurgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P3 live-state purge HTTP contract: {@code POST /admin/purge-live-alarms} returns 200 with the
 * per-table deleted-count summary JSON, and a second call on an empty store returns all zeros.
 */
class AdminControllerTest {

    private MockMvc mvc;
    private PurgeService purgeService;

    @BeforeEach
    void setUp() {
        purgeService = Mockito.mock(PurgeService.class);
        AdminController controller = new AdminController(purgeService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void purgeReturns200WithSummaryJson() throws Exception {
        when(purgeService.purgeLiveAlarms()).thenReturn(new PurgeSummary(3, 7, 2, 5));

        mvc.perform(post("/admin/purge-live-alarms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedAlarms").value(3))
                .andExpect(jsonPath("$.purgedTransitions").value(7))
                .andExpect(jsonPath("$.purgedPendingStatus").value(2))
                .andExpect(jsonPath("$.purgedProcessedEvents").value(5));
    }

    @Test
    void secondPurgeOnEmptyStoreReturnsZeros() throws Exception {
        when(purgeService.purgeLiveAlarms()).thenReturn(new PurgeSummary(0, 0, 0, 0));

        mvc.perform(post("/admin/purge-live-alarms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedAlarms").value(0))
                .andExpect(jsonPath("$.purgedTransitions").value(0))
                .andExpect(jsonPath("$.purgedPendingStatus").value(0))
                .andExpect(jsonPath("$.purgedProcessedEvents").value(0));
    }
}
