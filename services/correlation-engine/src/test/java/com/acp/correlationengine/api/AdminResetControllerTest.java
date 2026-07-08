package com.acp.correlationengine.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.correlationengine.observability.CorrelationMetrics;
import com.acp.correlationengine.support.EngineHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.acp.correlationengine.support.Fixtures.T0;
import static com.acp.correlationengine.support.Fixtures.alarm;
import static com.acp.correlationengine.support.Fixtures.gapPattern;

import java.util.List;

/**
 * HTTP-layer acceptance for {@code POST /admin/reset-correlation}: returns 200 with the
 * {@code { purgedIncidents, purgedIncidentAlarms, resetInMemory }} body, and is idempotent (second
 * call → zeros, 200). Standalone MockMvc over the real controller + reset service driving the real
 * engine, so the HTTP contract is exercised end-to-end without a broker/DB.
 */
class AdminResetControllerTest {

    private EngineHarness h;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        h = new EngineHarness();
        h.addPattern(gapPattern("P", "T", List.of("LOS", "LinkDown"), "LOS", 60_000));
        h.feed(alarm("a1", "LOS"), "T", T0);
        h.feed(alarm("a2", "LinkDown"), "T", T0 + 1); // -> 1 incident (root + child)

        CorrelationResetService service =
                new CorrelationResetService(h.incidents, h.engine, CorrelationMetrics.NOOP);
        mvc = MockMvcBuilders.standaloneSetup(new AdminResetController(service)).build();
    }

    /** POST returns 200 with the purge counts + resetInMemory=true. */
    @Test
    void post_returns200_withPurgeCountsAndResetFlag() throws Exception {
        mvc.perform(post("/admin/reset-correlation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedIncidents").value(1))
                .andExpect(jsonPath("$.purgedIncidentAlarms").value(2))
                .andExpect(jsonPath("$.resetInMemory").value(true));
    }

    /** Second call is idempotent — zeros, 200, no error. */
    @Test
    void post_isIdempotent_secondCallReturnsZeros() throws Exception {
        mvc.perform(post("/admin/reset-correlation")).andExpect(status().isOk());

        mvc.perform(post("/admin/reset-correlation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgedIncidents").value(0))
                .andExpect(jsonPath("$.purgedIncidentAlarms").value(0))
                .andExpect(jsonPath("$.resetInMemory").value(true));
    }
}
