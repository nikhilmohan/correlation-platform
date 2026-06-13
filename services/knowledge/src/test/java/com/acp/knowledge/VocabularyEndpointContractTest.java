package com.acp.knowledge;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * Provider-side contract test for the FROZEN {@code GET /domains/{domain}/vocabulary} surface
 * (design "Frozen integration contracts — A", gap P1-G11). Asserts the response shape Topology
 * builds its {@code KnowledgeVocabClient} against: {@code {domain, objectTypes[], relations[],
 * version}}, that the live core-ip response contains {@code Interface}/{@code HOSTS}/
 * {@code TERMINATES}, and that an unknown domain returns 404.
 */
class VocabularyEndpointContractTest extends AbstractKnowledgeIT {

    @Test
    void vocabulary_frozenShape_coreIpContents_and404() throws Exception {
        SeedFixtures.seedCoreIpVocabularies(mockMvc);

        mockMvc.perform(get("/domains/core-ip/vocabulary"))
                .andExpect(status().isOk())
                // exact frozen field set.
                .andExpect(jsonPath("$.domain", is("core-ip")))
                .andExpect(jsonPath("$.objectTypes").exists())
                .andExpect(jsonPath("$.relations").exists())
                .andExpect(jsonPath("$.version").exists())
                // frozen contents Topology validates snapshots against.
                .andExpect(jsonPath("$.objectTypes", hasItem("Interface")))
                .andExpect(jsonPath("$.relations", hasItem("HOSTS")))
                .andExpect(jsonPath("$.relations", hasItem("TERMINATES")));

        mockMvc.perform(get("/domains/unknown/vocabulary"))
                .andExpect(status().isNotFound());
    }
}
