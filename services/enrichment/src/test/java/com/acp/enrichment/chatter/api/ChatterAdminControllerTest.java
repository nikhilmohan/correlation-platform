package com.acp.enrichment.chatter.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.enrichment.chatter.ChatterService;
import com.acp.enrichment.chatter.ChatterService.ChatterValidationException;
import com.acp.enrichment.ruleset.ChatterEntry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Acceptance criteria 18, 20 — chatter API HTTP status mapping (200/201/204/400/404/409). */
@WebMvcTest(ChatterAdminController.class)
class ChatterAdminControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    ChatterService chatterService;

    @Test
    void getReturnsEntries() throws Exception {
        when(chatterService.list("nms-alpha")).thenReturn(List.of(
                new ChatterEntry("Interface:edge1-12", "communicationsAlarm", "LinkDown", null)));
        mvc.perform(get("/api/v1/sources/nms-alpha/chatter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("nms-alpha"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entries[0].managedObjectId").value("Interface:edge1-12"));
    }

    @Test
    void getUnknownSourceReturns404() throws Exception {
        when(chatterService.list("nope")).thenThrow(
                new ChatterValidationException("unknown_source", "no ruleset"));
        mvc.perform(get("/api/v1/sources/nope/chatter"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("unknown_source"));
    }

    @Test
    void postAddsEntryReturns201() throws Exception {
        when(chatterService.add(eq("nms-alpha"), any())).thenReturn(
                new ChatterEntry("Interface:edge1-7", "communicationsAlarm", null, null));
        mvc.perform(post("/api/v1/sources/nms-alpha/chatter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managedObjectId\":\"Interface:edge1-7\","
                                + "\"eventType\":\"communicationsAlarm\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.managedObjectId").value("Interface:edge1-7"));
    }

    @Test
    void postMalformedReturns400() throws Exception {
        when(chatterService.add(eq("nms-alpha"), any())).thenThrow(
                new ChatterValidationException("malformed_entry", "needs key"));
        mvc.perform(post("/api/v1/sources/nms-alpha/chatter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"communicationsAlarm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_entry"));
    }

    @Test
    void postDuplicateReturns409() throws Exception {
        when(chatterService.add(eq("nms-alpha"), any())).thenThrow(
                new ChatterValidationException("duplicate_entry", "present"));
        mvc.perform(post("/api/v1/sources/nms-alpha/chatter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managedObjectId\":\"Interface:edge1-12\","
                                + "\"eventType\":\"communicationsAlarm\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_entry"));
    }

    @Test
    void deleteReturns204() throws Exception {
        doNothing().when(chatterService).remove(eq("nms-alpha"), any());
        mvc.perform(delete("/api/v1/sources/nms-alpha/chatter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managedObjectId\":\"Interface:edge1-12\","
                                + "\"eventType\":\"communicationsAlarm\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAbsentReturns404() throws Exception {
        doThrow(new ChatterValidationException("entry_not_present", "absent"))
                .when(chatterService).remove(eq("nms-alpha"), any());
        mvc.perform(delete("/api/v1/sources/nms-alpha/chatter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managedObjectId\":\"Interface:x\","
                                + "\"eventType\":\"communicationsAlarm\"}"))
                .andExpect(status().isNotFound());
    }
}
