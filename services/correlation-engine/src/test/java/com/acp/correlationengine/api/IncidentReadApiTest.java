package com.acp.correlationengine.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.correlationengine.incident.InMemoryIncidentRepository;
import com.acp.correlationengine.incident.IncidentRepository;
import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Read-API acceptance criteria over {@code GET /incidents} + {@code GET /incidents/{id}} (AC18,
 * AC24, AC29). Standalone MockMvc over the real controller + in-memory repository.
 */
class IncidentReadApiTest {

    private final IncidentRepository repo = new InMemoryIncidentRepository();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new IncidentQueryController(repo))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        repo.save(incident("INC-1", "T1", "root1", "LOS", List.of("c1", "c2"),
                "PAT-1", null, MatchCandidate.MatchType.PATTERN));
        repo.save(incident("INC-2", "T2", "root2", "PortDown", List.of("c3"),
                null, "CODEBOOK-2", MatchCandidate.MatchType.CODEBOOK));
    }

    /** AC24 — GET /incidents returns the canonical {items, total, limit, offset} envelope. */
    @Test
    void ac24_incidentsReturnsCanonicalPaginationEnvelope() throws Exception {
        mvc.perform(get("/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0));
    }

    /** AC29 — every incident carries rootCauseAlarmId AND rootCauseAlarmType. */
    @Test
    void ac29_incidentCarriesRootCauseAlarmIdAndType() throws Exception {
        mvc.perform(get("/incidents/INC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentId").value("INC-1"))
                .andExpect(jsonPath("$.rootCauseAlarmId").value("root1"))
                .andExpect(jsonPath("$.rootCauseAlarmType").value("LOS"))
                .andExpect(jsonPath("$.matchedPatternId").value("PAT-1"));
    }

    /** AC18 — GET /incidents/{id} root cause + children match the emitted values. */
    @Test
    void ac18_incidentByIdReturnsRootCauseAndChildren() throws Exception {
        mvc.perform(get("/incidents/INC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootCauseAlarmId").value("root1"))
                .andExpect(jsonPath("$.childAlarmIds[0]").value("c1"))
                .andExpect(jsonPath("$.childAlarmIds[1]").value("c2"));
    }

    @Test
    void unknownIncident_returns404() throws Exception {
        mvc.perform(get("/incidents/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void invalidMatchType_returns400() throws Exception {
        mvc.perform(get("/incidents").param("matchType", "banana"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filterByMatchType_narrowsResults() throws Exception {
        mvc.perform(get("/incidents").param("matchType", "codebook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].matchedCodebookId").value("CODEBOOK-2"));
    }

    private static Incident incident(String id, String trail, String root, String rootType,
            List<String> children, String patternId, String codebookId,
            MatchCandidate.MatchType type) {
        return new Incident(id, trail, root, rootType, children, patternId, codebookId, 0.9,
                type, "fp-" + id, Instant.parse("2026-06-11T12:00:00Z"));
    }
}
