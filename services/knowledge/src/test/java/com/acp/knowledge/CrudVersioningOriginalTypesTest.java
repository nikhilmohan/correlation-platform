package com.acp.knowledge;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * AC1 — CRUD + versioning for the four original record types. Create returns v1; update returns
 * v2 while v1 stays retrievable. The four original types: propagationTemplate, faultOriginType,
 * trailPolicy, modelParams.
 */
class CrudVersioningOriginalTypesTest extends AbstractKnowledgeIT {

    @Test
    void createAndUpdate_mintsAndRetainsVersions() throws Exception {
        // Seed the core-ip vocabularies first so cross-record validation passes.
        SeedFixtures.seedCoreIpVocabularies(mockMvc);

        // --- propagationTemplate ---
        String tmplV1 = """
            {"recordId":"core-ip/propagationTemplate/HOSTS","payload":{
              "edgeType":"HOSTS",
              "trigger":{"objectType":"Port","alarmType":"PortDown"},
              "effect":{"objectType":"Interface","alarmType":"InterfaceDown"},
              "traversal":{"direction":"forward","cardinality":"each-target"}}}""";
        mockMvc.perform(post("/domains/core-ip/propagation-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(tmplV1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")));

        String tmplV2 = """
            {"payload":{
              "edgeType":"HOSTS",
              "trigger":{"objectType":"Port","alarmType":"PortDown"},
              "effect":{"objectType":"Interface","alarmType":"CRCErrors"},
              "traversal":{"direction":"forward","cardinality":"each-target"}}}""";
        mockMvc.perform(put("/domains/core-ip/propagation-templates/"
                        + enc("core-ip/propagationTemplate/HOSTS"))
                        .contentType(MediaType.APPLICATION_JSON).content(tmplV2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        // v1 still retrievable, unchanged.
        mockMvc.perform(get("/domains/core-ip/propagation-templates/"
                        + enc("core-ip/propagationTemplate/HOSTS") + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.payload.effect.alarmType", is("InterfaceDown")));

        // --- faultOriginType ---
        createUpdate("fault-origin-types", "core-ip/faultOriginType/Interface",
                """
                {"recordId":"core-ip/faultOriginType/Interface","payload":{
                  "objectType":"Interface","originAlarmType":"InterfaceDown"}}""",
                """
                {"payload":{"objectType":"Interface","originAlarmType":"InterfaceDown",
                  "description":"updated"}}""");

        // --- trailPolicy ---
        createUpdate("trail-policies", "core-ip/trailPolicy/default",
                """
                {"recordId":"core-ip/trailPolicy/default","payload":{
                  "closureEdgeTypes":["HOSTS","TERMINATES"],
                  "boundary":{"type":"igp-area","attributeKey":"igpArea"},
                  "srlgRule":{"mode":"union-members","srlgEdgeType":"MEMBER_OF"}}}""",
                """
                {"payload":{
                  "closureEdgeTypes":["HOSTS","TERMINATES","SERVES"],
                  "boundary":{"type":"igp-area","attributeKey":"igpArea"},
                  "srlgRule":{"mode":"union-members","srlgEdgeType":"MEMBER_OF"}}}""");

        // --- modelParams ---
        createUpdate("model-params", "core-ip/modelParams/pattern-miner",
                """
                {"recordId":"core-ip/modelParams/pattern-miner","payload":{
                  "paramSet":"pattern-miner","params":[
                    {"key":"prefixspan.minSupport","type":"number","value":0.3,"min":0.0,"max":1.0}]}}""",
                """
                {"payload":{"paramSet":"pattern-miner","params":[
                    {"key":"prefixspan.minSupport","type":"number","value":0.4,"min":0.0,"max":1.0}]}}""");
    }

    private void createUpdate(String segment, String recordId, String createBody,
            String updateBody) throws Exception {
        mockMvc.perform(post("/domains/core-ip/" + segment)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")));
        mockMvc.perform(put("/domains/core-ip/" + segment + "/" + enc(recordId))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));
        mockMvc.perform(get("/domains/core-ip/" + segment + "/" + enc(recordId) + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v1")));
    }

    static String enc(String recordId) {
        return java.net.URLEncoder.encode(recordId, java.nio.charset.StandardCharsets.UTF_8);
    }
}
