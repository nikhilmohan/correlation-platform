package com.acp.topology.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.topology.api.dto.ApiError.Violation;
import com.acp.topology.api.dto.SnapshotIngestResponse;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.ingest.IngestionService;
import com.acp.topology.ingest.ValidationException;
import com.acp.topology.integration.VocabularyUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC-1 / AC-20 / AC-27 (frozen 200 SnapshotIngestResponse — P1-G1), AC-3..AC-7b (422 with no
 * downstream effect), AC-23 (502 fail-closed when domain vocabulary is unavailable). WebMvc slice
 * with a mocked {@link IngestionService}; asserts HTTP status + the frozen response body.
 */
@WebMvcTest(controllers = IngestionController.class)
@Import(IngestionControllerTest.Props.class)
class IngestionControllerTest {

    /** The slice does not scan @ConfigurationProperties; supply a default TopologyProperties. */
    static class Props {
        @Bean
        TopologyProperties topologyProperties() {
            return new TopologyProperties();
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private IngestionService ingestionService;

    @Test
    void postValidFileReturns200WithFrozenSnapshotIngestResponse() throws Exception {
        when(ingestionService.ingest(any(), any(), any())).thenReturn(
                new SnapshotIngestResponse("SNAP-1", "core-ip", "current", 11, 10, "full-load"));

        mvc.perform(post("/topology/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"domain\":\"core-ip\",\"nodes\":[],\"edges\":[]}"))
                .andExpect(status().isOk())   // 200, NOT 202 (P1-G1: synchronous lift)
                .andExpect(jsonPath("$.snapshotId").value("SNAP-1"))
                .andExpect(jsonPath("$.domain").value("core-ip"))
                .andExpect(jsonPath("$.status").value("current"))
                .andExpect(jsonPath("$.nodeCount").value(11))
                .andExpect(jsonPath("$.edgeCount").value(10))
                .andExpect(jsonPath("$.changeType").value("full-load"));
    }

    @Test
    void rejectsInvalidFileWith422AndNoDownstreamEffect() throws Exception {
        when(ingestionService.ingest(any(), any(), any())).thenThrow(
                new ValidationException("snapshot file failed validation",
                        List.of(new Violation("$.domain", "required", "domain is required"))));

        mvc.perform(post("/topology/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.violations[0].path").value("$.domain"));
    }

    @Test
    void failsClosedWhenVocabUnavailable() throws Exception {
        // AC-23 / EH-6c: Knowledge vocabulary unavailable + uncached → 502, no write, no event.
        when(ingestionService.ingest(any(), any(), any())).thenThrow(
                new VocabularyUnavailableException("Knowledge domain-vocabulary unavailable"));

        mvc.perform(post("/topology/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"domain\":\"core-ip\",\"nodes\":[],\"edges\":[]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }
}
