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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * AC2/AC3/AC4 — CRUD + versioning for the object-type vocabulary (incl. Interface), the
 * edge-relation vocabulary (incl. HOSTS/TERMINATES), and the attribute catalogue.
 * AC5/AC6 — token-format validation rejects malformed entries with 422 naming the entry,
 * nothing persisted.
 */
class VocabularyCrudAndValidationTest extends AbstractKnowledgeIT {

    @Test
    void objectTypeVocabulary_crudVersions_andInterfacePresent() throws Exception {
        String v1 = """
            {"recordId":"core-ip/objectTypeVocabulary/default","payload":{
              "objectTypes":["Node","LineCard","Port","Interface","IPLink","IGPAdjacency",
                "LSP","VPNService","FiberSpan","SRLG","Site"]}}""";
        mockMvc.perform(post("/domains/core-ip/object-type-vocabulary")
                        .contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.payload.objectTypes", hasItem("Interface")));

        String v2 = """
            {"payload":{"objectTypes":["Node","LineCard","Port","Interface","IPLink",
              "IGPAdjacency","LSP","VPNService","FiberSpan","SRLG","Site","NewType"]}}""";
        mockMvc.perform(put("/domains/core-ip/object-type-vocabulary/"
                        + enc("core-ip/objectTypeVocabulary/default"))
                        .contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        mockMvc.perform(get("/domains/core-ip/object-type-vocabulary/"
                        + enc("core-ip/objectTypeVocabulary/default") + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.objectTypes", hasItem("Interface")));
    }

    @Test
    void edgeRelationVocabulary_crudVersions_andHostsTerminatesPresent() throws Exception {
        String v1 = """
            {"recordId":"core-ip/edgeRelationVocabulary/default","payload":{
              "relations":["HOSTED_ON","HOSTS","TERMINATES","RIDES_ON","ADJACENCY_OVER",
                "TRAVERSES","SERVES","MEMBER_OF","LOCATED_AT"]}}""";
        mockMvc.perform(post("/domains/core-ip/edge-relation-vocabulary")
                        .contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.payload.relations", hasItem("HOSTS")))
                .andExpect(jsonPath("$.payload.relations", hasItem("TERMINATES")));

        String v2 = """
            {"payload":{"relations":["HOSTED_ON","HOSTS","TERMINATES","RIDES_ON",
              "ADJACENCY_OVER","TRAVERSES","SERVES","MEMBER_OF","LOCATED_AT","NEW_REL"]}}""";
        mockMvc.perform(put("/domains/core-ip/edge-relation-vocabulary/"
                        + enc("core-ip/edgeRelationVocabulary/default"))
                        .contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        mockMvc.perform(get("/domains/core-ip/edge-relation-vocabulary/"
                        + enc("core-ip/edgeRelationVocabulary/default") + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.relations", hasItem("HOSTS")));
    }

    @Test
    void attributeCatalogue_crudVersions() throws Exception {
        String v1 = """
            {"recordId":"core-ip/attributeCatalogue/default","payload":{
              "deviceKeys":[{"key":"vendor","valueForm":"string"},
                {"key":"equipmentType","valueForm":"enum","allowed":["router","switch"]}],
              "connectionKeys":[{"key":"linkType","valueForm":"string"}]}}""";
        mockMvc.perform(post("/domains/core-ip/attribute-catalogue")
                        .contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")));

        String v2 = """
            {"payload":{
              "deviceKeys":[{"key":"vendor","valueForm":"string"},{"key":"igpArea","valueForm":"string"}],
              "connectionKeys":[{"key":"linkType","valueForm":"string"}]}}""";
        mockMvc.perform(put("/domains/core-ip/attribute-catalogue/"
                        + enc("core-ip/attributeCatalogue/default"))
                        .contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        mockMvc.perform(get("/domains/core-ip/attribute-catalogue/"
                        + enc("core-ip/attributeCatalogue/default") + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.deviceKeys[1].key", is("equipmentType")));
    }

    @Test
    void objectTypeVocabulary_rejectsBadTokenFormat_422_named_nothingPersisted() throws Exception {
        String bad = """
            {"recordId":"core-ip/objectTypeVocabulary/bad","payload":{
              "objectTypes":["Node","123Invalid"]}}""";
        mockMvc.perform(post("/domains/core-ip/object-type-vocabulary")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("validation_failed")))
                .andExpect(jsonPath("$.violations[0].message", containsString("123Invalid")));

        // Nothing persisted.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge.record WHERE record_id = 'core-ip/objectTypeVocabulary/bad'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, rows);
    }

    @Test
    void edgeRelationVocabulary_rejectsBadTokenFormat_422_named() throws Exception {
        String bad = """
            {"recordId":"core-ip/edgeRelationVocabulary/bad","payload":{
              "relations":["HOSTS","9BadRel"]}}""";
        mockMvc.perform(post("/domains/core-ip/edge-relation-vocabulary")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("validation_failed")))
                .andExpect(jsonPath("$.violations[0].message", containsString("9BadRel")));
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge.record WHERE record_id = 'core-ip/edgeRelationVocabulary/bad'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, rows);
    }
}
