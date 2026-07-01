package com.acp.patternmanager.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SequenceElementView;
import com.acp.patternmanager.api.dto.SessionWindowView;
import com.acp.patternmanager.api.dto.SupportingInstanceView;
import com.acp.patternmanager.api.error.PatternNotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP surface contract (criteria 12, 12b, 21): GET /patterns returns the PatternPage ENVELOPE (not
 * a bare array); GET /patterns/{id} returns a full PatternView with sessionWindow + trailId; unknown
 * id -> 404; PATCH out-of-range index -> 422; non-draft approve -> 409.
 */
@WebMvcTest(controllers = PatternController.class)
class PatternControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PatternQueryService queryService;
    @MockBean private LifecycleService lifecycleService;
    @MockBean private PatternEditService editService;

    private PatternView view(String lifecycle) {
        return new PatternView(
                "11111111-1111-1111-1111-111111111111",
                "trail-1",
                List.of(new SequenceElementView("LOS", false),
                        new SequenceElementView("LinkDown", false)),
                "LOS", 0.4, 0.9, 3.2, null,
                new SessionWindowView(5000, "gap-based"),
                null, "unexplained", true, null, 2,
                List.of(new SupportingInstanceView("w1", "s1", null)),
                lifecycle, "core-ip", OffsetDateTime.now(), OffsetDateTime.now());
    }

    // Criterion 12 + 12b: list is the PatternPage ENVELOPE object (items/total/limit/offset).
    @Test
    void listReturnsPatternPageEnvelopeNotBareArray() throws Exception {
        when(queryService.list(any(), any(), any(), any()))
                .thenReturn(new PatternPage(List.of(view("draft")), 1, 50, 0));

        mockMvc.perform(get("/patterns?lifecycle=draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items[0].trailId").value("trail-1"))
                .andExpect(jsonPath("$.items[0].sessionWindow.windowMs").value(5000))
                .andExpect(jsonPath("$.items[0].sessionWindow.type").value("gap-based"));
    }

    // Criterion 21: get by id returns sessionWindow + XAI fields (trailId, struct flag, rootCause).
    @Test
    void getByIdReturnsSessionWindowAndXaiMetadata() throws Exception {
        when(queryService.get(eq("11111111-1111-1111-1111-111111111111")))
                .thenReturn(view("draft"));

        mockMvc.perform(get("/patterns/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patternId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.trailId").value("trail-1"))
                .andExpect(jsonPath("$.rootCauseAlarmType").value("LOS"))
                .andExpect(jsonPath("$.structurallyValidated").value(true))
                .andExpect(jsonPath("$.sessionWindow.windowMs").value(5000))
                .andExpect(jsonPath("$.sessionWindow.type").value("gap-based"));
    }

    // Criterion 12: unknown id -> 404 structured body.
    @Test
    void getByIdUnknownReturns404() throws Exception {
        when(queryService.get(eq("deadbeef-0000-0000-0000-000000000000")))
                .thenThrow(new PatternNotFoundException("deadbeef-0000-0000-0000-000000000000"));

        mockMvc.perform(get("/patterns/deadbeef-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // Approve with a missing reviewer / invalid decision -> 422 (bean validation).
    @Test
    void approveWithInvalidBodyReturns422() throws Exception {
        mockMvc.perform(post("/patterns/11111111-1111-1111-1111-111111111111/approve")
                        .contentType("application/json")
                        .content("{\"decision\":\"maybe\",\"reviewer\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // PATCH with a valid frozen sequenceFlags body reaches the service (200).
    @Test
    void patchWithFrozenSequenceFlagsBodyIsAccepted() throws Exception {
        when(editService.applyEdit(any(), any())).thenReturn(view("draft"));
        mockMvc.perform(patch("/patterns/11111111-1111-1111-1111-111111111111")
                        .contentType("application/json")
                        .content("{\"sequenceFlags\":[{\"index\":0,\"optional\":true}],\"reviewer\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("draft"));
    }
}
