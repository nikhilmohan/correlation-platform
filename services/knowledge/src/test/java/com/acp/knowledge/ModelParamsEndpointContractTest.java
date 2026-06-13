package com.acp.knowledge;

import static com.acp.knowledge.CrudVersioningOriginalTypesTest.enc;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Provider-side contract test for the FROZEN model-params read/edit surface (design "Frozen
 * integration contracts — B", gap P2-GAP-07). The read returns the versioned envelope with the
 * dotted-key {@code params[]} payload; a PUT mints a new version (old version still retrievable);
 * an out-of-bounds value is rejected 422 naming the param. The web-ui builds its Knowledge client
 * against this shape.
 */
class ModelParamsEndpointContractTest extends AbstractKnowledgeIT {

    private static final String RECORD_ID = "core-ip/modelParams/noise-filter";

    @Test
    void modelParams_versionedEnvelope_dottedKeys_writeMintsVersion_outOfBoundsRejected()
            throws Exception {
        // Create the noise-filter param set with the real dotted keys.
        mockMvc.perform(post("/domains/core-ip/model-params").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"recordId":"core-ip/modelParams/noise-filter","payload":{
                              "paramSet":"noise-filter","params":[
                                {"key":"dbscan.epsilon","type":"number","value":0.5,"min":0.0,"max":100.0},
                                {"key":"dbscan.minSamples","type":"integer","value":3,"min":1,"max":1000},
                                {"key":"window.sizeSeconds","type":"integer","value":60,"min":1,"max":86400,"unit":"s"}]}}"""))
                .andExpect(status().isCreated());

        // Read current → versioned envelope with the dotted-key params[] payload (frozen shape).
        mockMvc.perform(get("/domains/core-ip/model-params/" + enc(RECORD_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain", is("core-ip")))
                .andExpect(jsonPath("$.recordType", is("modelParams")))
                .andExpect(jsonPath("$.recordId", is(RECORD_ID)))
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.isCurrent", is(true)))
                .andExpect(jsonPath("$.payload.paramSet", is("noise-filter")))
                .andExpect(jsonPath("$.payload.params[0].key", is("dbscan.epsilon")));

        // PUT mints a new version (immutable write); old version still retrievable.
        mockMvc.perform(put("/domains/core-ip/model-params/" + enc(RECORD_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"payload":{"paramSet":"noise-filter","params":[
                                {"key":"dbscan.epsilon","type":"number","value":0.8,"min":0.0,"max":100.0}]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        mockMvc.perform(get("/domains/core-ip/model-params/" + enc(RECORD_ID) + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.payload.params[0].value", is(0.5)));

        // Out-of-bounds value (prefixspan.minSupport = 1.5) rejected 422 naming the param.
        mockMvc.perform(post("/domains/core-ip/model-params").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"recordId":"core-ip/modelParams/pattern-miner","payload":{
                              "paramSet":"pattern-miner","params":[
                                {"key":"prefixspan.minSupport","type":"number","value":1.5,"min":0.0,"max":1.0}]}}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message", containsString("prefixspan.minSupport")));
    }
}
