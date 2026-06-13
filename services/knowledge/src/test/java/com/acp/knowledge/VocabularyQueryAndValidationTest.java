package com.acp.knowledge;

import static com.acp.knowledge.CrudVersioningOriginalTypesTest.enc;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * AC7 — the vocabulary query endpoint serves a domain's object-type AND edge-relation sets in one
 * call; an unknown domain is 404.
 * AC9 — an invalid propagation template (unknown edge type) is rejected 422, naming the offending
 * field + rule, nothing persisted.
 * AC10 — an out-of-bounds model-params value is rejected 422, naming the offending param.
 * AC20 — the core-ip vocabulary query response contains {@code Interface}, {@code HOSTS},
 * {@code TERMINATES}.
 */
class VocabularyQueryAndValidationTest extends AbstractKnowledgeIT {

    // AC7 + AC20 — vocabulary query serves both sets; core-ip contains Interface/HOSTS/TERMINATES.
    @Test
    void vocabularyQuery_servesBothSets_andUnknownDomainIs404() throws Exception {
        SeedFixtures.seedCoreIpVocabularies(mockMvc);

        mockMvc.perform(get("/domains/core-ip/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain", is("core-ip")))
                .andExpect(jsonPath("$.objectTypes", hasItem("Interface")))
                .andExpect(jsonPath("$.objectTypes", hasItem("Node")))
                .andExpect(jsonPath("$.relations", hasItem("HOSTS")))
                .andExpect(jsonPath("$.relations", hasItem("TERMINATES")))
                .andExpect(jsonPath("$.relations", hasItem("HOSTED_ON")))
                .andExpect(jsonPath("$.version", is("v1")));

        // AC7 — unknown domain → 404.
        mockMvc.perform(get("/domains/no-such-domain/vocabulary"))
                .andExpect(status().isNotFound());
    }

    // AC9 — propagation template referencing an unknown edge type → 422 naming the field + rule.
    @Test
    void propagationTemplate_unknownEdgeType_rejected422_named_nothingPersisted() throws Exception {
        SeedFixtures.seedCoreIpVocabularies(mockMvc);

        String bad = """
            {"recordId":"core-ip/propagationTemplate/BAD","payload":{
              "edgeType":"UNKNOWN_EDGE",
              "trigger":{"objectType":"Port","alarmType":"PortDown"},
              "effect":{"objectType":"Interface","alarmType":"InterfaceDown"},
              "traversal":{"direction":"forward","cardinality":"each-target"}}}""";
        mockMvc.perform(post("/domains/core-ip/propagation-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("validation_failed")))
                .andExpect(jsonPath("$.recordType", is("propagationTemplate")))
                .andExpect(jsonPath("$.violations[0].field", is("edgeType")))
                .andExpect(jsonPath("$.violations[0].rule", is("edge-type-in-vocabulary")))
                .andExpect(jsonPath("$.violations[0].message", containsString("UNKNOWN_EDGE")));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge.record WHERE record_id = 'core-ip/propagationTemplate/BAD'",
                Integer.class);
        Assertions.assertEquals(0, rows);
    }

    // AC10 — out-of-bounds model-params value (minSupport > 1) → 422 naming the offending param.
    @Test
    void modelParams_outOfBounds_rejected422_namedParam_nothingPersisted() throws Exception {
        String bad = """
            {"recordId":"core-ip/modelParams/pattern-miner","payload":{
              "paramSet":"pattern-miner","params":[
                {"key":"prefixspan.minSupport","type":"number","value":1.5,"min":0.0,"max":1.0}]}}""";
        mockMvc.perform(post("/domains/core-ip/model-params")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("validation_failed")))
                .andExpect(jsonPath("$.violations[0].field", is("prefixspan.minSupport")))
                .andExpect(jsonPath("$.violations[0].rule", is("param-bounds")))
                .andExpect(jsonPath("$.violations[0].message",
                        containsString("prefixspan.minSupport")));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge.record WHERE record_id = 'core-ip/modelParams/pattern-miner'",
                Integer.class);
        Assertions.assertEquals(0, rows);
    }

    // AC11 — version pinning: after two updates, pinning v1 returns the original content while the
    // current read returns v2.
    @Test
    void versionPinning_v1RetrievableAfterUpdate_currentReturnsLatest() throws Exception {
        String segment = "model-params";
        String recordId = "core-ip/modelParams/noise-filter";

        mockMvc.perform(post("/domains/core-ip/" + segment).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"recordId":"core-ip/modelParams/noise-filter","payload":{
                              "paramSet":"noise-filter","params":[
                                {"key":"dbscan.epsilon","type":"number","value":0.5,"min":0.0,"max":100.0}]}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")));

        mockMvc.perform(put("/domains/core-ip/" + segment + "/" + enc(recordId))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"payload":{"paramSet":"noise-filter","params":[
                                {"key":"dbscan.epsilon","type":"number","value":0.7,"min":0.0,"max":100.0}]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        // Pinned v1 returns the original value.
        mockMvc.perform(get("/domains/core-ip/" + segment + "/" + enc(recordId) + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.payload.params[0].value", is(0.5)));

        // Current (no version) returns v2.
        mockMvc.perform(get("/domains/core-ip/" + segment + "/" + enc(recordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")))
                .andExpect(jsonPath("$.isCurrent", is(true)))
                .andExpect(jsonPath("$.payload.params[0].value", is(0.7)));
    }
}
