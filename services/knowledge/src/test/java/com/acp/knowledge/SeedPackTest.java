package com.acp.knowledge;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Core IP seed pack loads through the SAME validated write path (dogfood) and matches the
 * design's magnitudes: a single object-type/edge-relation/alarm-type vocabulary record, 7
 * fault-origin types, 27 propagation templates, 4 model-param sets, and the attribute catalogue
 * carrying the well-known {@code igpArea} device key (design "Seed data"). This proves the seed is
 * not rejected by validation (vocabularies authored first, then cross-referencing records) and
 * that onboarding the MVP domain is records-only.
 */
class SeedPackTest extends AbstractKnowledgeIT {

    @Autowired
    private com.acp.knowledge.domain.RecordService recordService;

    @Autowired
    private com.acp.knowledge.store.RecordStore recordStore;

    private com.acp.knowledge.seed.SeedLoader seedLoader;

    @BeforeEach
    void loadSeed() throws Exception {
        // The startup seeder bean is conditionally disabled in the base IT (on-startup=false), so
        // construct the loader directly from its collaborators and load the pack through the SAME
        // validated write path here (dogfood-validated seed).
        seedLoader = new com.acp.knowledge.seed.SeedLoader(recordService, recordStore, objectMapper,
                new com.acp.knowledge.config.SeedProperties(true));
        seedLoader.loadPack("seed/core-ip.json");
    }

    @Test
    void alarmTypeVocabulary_has29GroundedTokens() throws Exception {
        mockMvc.perform(get("/domains/core-ip/alarm-type-vocabulary/"
                        + CrudVersioningOriginalTypesTest.enc("core-ip/alarmTypeVocabulary/default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.alarmTypes", hasSize(29)))
                .andExpect(jsonPath("$.payload.alarmTypes", hasItem("InterfaceDown")))
                .andExpect(jsonPath("$.payload.alarmTypes", hasItem("FiberCut")));
    }

    @Test
    void propagationTemplates_27Seeded() throws Exception {
        mockMvc.perform(get("/domains/core-ip/propagation-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(27)));
    }

    @Test
    void faultOriginTypes_7Seeded_andModelParams_4Seeded() throws Exception {
        mockMvc.perform(get("/domains/core-ip/fault-origin-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(get("/domains/core-ip/model-params"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void attributeCatalogue_carriesIgpArea_andTrailPolicyBoundsOnIt() throws Exception {
        String catalogue = mockMvc.perform(get("/domains/core-ip/attribute-catalogue/"
                        + CrudVersioningOriginalTypesTest.enc("core-ip/attributeCatalogue/default")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Assertions.assertTrue(catalogue.contains("igpArea"),
                "attribute catalogue must carry the well-known igpArea device key");

        // The trail policy bounds closure on igpArea — the boundary key resolves to the catalogued
        // attribute (design fix A2).
        mockMvc.perform(get("/domains/core-ip/trail-policies/"
                        + CrudVersioningOriginalTypesTest.enc("core-ip/trailPolicy/default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.boundary.attributeKey", is("igpArea")));
    }

    @Test
    void vocabularyQuery_servesSeededCoreIpSets() throws Exception {
        mockMvc.perform(get("/domains/core-ip/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectTypes", hasItem("Interface")))
                .andExpect(jsonPath("$.relations", hasItem("HOSTS")))
                .andExpect(jsonPath("$.relations", hasItem("TERMINATES")));
    }

    @Test
    void seedIsIdempotent_secondLoadAddsNothing() throws Exception {
        int loadedAgain = seedLoader.loadPack("seed/core-ip.json");
        Assertions.assertEquals(0, loadedAgain, "re-loading the seed pack must add no new records");
    }
}
