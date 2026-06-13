package com.acp.knowledge;

import static com.acp.knowledge.CrudVersioningOriginalTypesTest.enc;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC14 — records carry a domain identifier; a domain-filtered list returns only that domain's
 * records, not another domain's.
 * AC16 — a non-Core-IP domain's vocabulary + catalogue records can be CRUDed and fetched (current
 * + pinned) without code change; the service does not reject a record just because the domain is
 * not {@code core-ip}.
 * AC17 — a non-Core-IP domain's propagation template can be CRUDed and fetched; edge-type
 * validation is driven by THAT domain's vocabulary, not a hard-coded Core IP list.
 * AC18 — {@code Interface} is accepted as a fault-origin type in core-ip (create/retrieve/update).
 * AC19 — the core-ip interface-cascade templates (HOSTS, TERMINATES, ADJACENCY_OVER from
 * InterfaceDown) can be created and are returned by a domain-scoped list.
 */
class DomainScopingAndExtensibilityTest extends AbstractKnowledgeIT {

    // AC14 — domain identifier present on reads; domain-filtered list isolates domains.
    @Test
    void records_carryDomain_andDomainScopedListIsIsolated() throws Exception {
        // core-ip object-type vocabulary.
        author(mockMvc, "core-ip", "object-type-vocabulary",
                """
                {"recordId":"core-ip/objectTypeVocabulary/default","payload":{
                  "objectTypes":["Node","Interface"]}}""");
        // other-domain object-type vocabulary.
        author(mockMvc, "other-domain", "object-type-vocabulary",
                """
                {"recordId":"other-domain/objectTypeVocabulary/default","payload":{
                  "objectTypes":["AlphaNode"]}}""");

        // Each record is returned with its own domain.
        mockMvc.perform(get("/domains/core-ip/object-type-vocabulary/"
                        + enc("core-ip/objectTypeVocabulary/default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain", is("core-ip")));

        // A list filtered by core-ip returns only the core-ip record (not other-domain's).
        mockMvc.perform(get("/domains/core-ip/object-type-vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].domain", is("core-ip")))
                .andExpect(jsonPath("$[0].recordId", is("core-ip/objectTypeVocabulary/default")));

        mockMvc.perform(get("/domains/other-domain/object-type-vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].domain", is("other-domain")));
    }

    // AC16 — non-core-ip vocabulary + catalogue CRUD + current/pinned fetch + domain filter.
    @Test
    void nonCoreIpDomain_vocabularyAndCatalogue_crudAndFetch() throws Exception {
        author(mockMvc, "other-domain", "object-type-vocabulary",
                """
                {"recordId":"other-domain/objectTypeVocabulary/default","payload":{
                  "objectTypes":["AlphaNode","BetaLink"]}}""");
        author(mockMvc, "other-domain", "edge-relation-vocabulary",
                """
                {"recordId":"other-domain/edgeRelationVocabulary/default","payload":{
                  "relations":["CONNECTS_TO","LOCATED_AT"]}}""");
        author(mockMvc, "other-domain", "attribute-catalogue",
                """
                {"recordId":"other-domain/attributeCatalogue/default","payload":{
                  "deviceKeys":[{"key":"vendor","valueForm":"string"}],
                  "connectionKeys":[{"key":"linkType","valueForm":"string"}]}}""");

        // Update the object-type vocabulary → v2, v1 still pinned.
        mockMvc.perform(put("/domains/other-domain/object-type-vocabulary/"
                        + enc("other-domain/objectTypeVocabulary/default"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"payload":{"objectTypes":["AlphaNode","BetaLink","GammaPort"]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        mockMvc.perform(get("/domains/other-domain/object-type-vocabulary/"
                        + enc("other-domain/objectTypeVocabulary/default") + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.objectTypes", hasSize(2)));

        // The vocabulary query works for the new domain (not rejected for being non-core-ip).
        mockMvc.perform(get("/domains/other-domain/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectTypes", hasItem("AlphaNode")))
                .andExpect(jsonPath("$.relations", hasItem("CONNECTS_TO")));
    }

    // AC17 — non-core-ip propagation template: validation driven by THAT domain's vocabulary.
    @Test
    void nonCoreIpDomain_propagationTemplate_crudAndFetch_domainDrivenValidation() throws Exception {
        author(mockMvc, "transport-otn", "object-type-vocabulary",
                """
                {"recordId":"transport-otn/objectTypeVocabulary/default","payload":{
                  "objectTypes":["OTNNode","ODUPath","OCHTrail","Site"]}}""");
        author(mockMvc, "transport-otn", "edge-relation-vocabulary",
                """
                {"recordId":"transport-otn/edgeRelationVocabulary/default","payload":{
                  "relations":["CARRIED_OVER","LOCATED_AT"]}}""");
        author(mockMvc, "transport-otn", "alarm-type-vocabulary",
                """
                {"recordId":"transport-otn/alarmTypeVocabulary/default","payload":{
                  "alarmTypes":["ODUDown","OCHDown"]}}""");

        // Create — edgeType CARRIED_OVER is valid in transport-otn (not in core-ip).
        mockMvc.perform(post("/domains/transport-otn/propagation-templates")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"recordId":"transport-otn/propagationTemplate/CARRIED_OVER","payload":{
                              "edgeType":"CARRIED_OVER",
                              "trigger":{"objectType":"OCHTrail","alarmType":"OCHDown"},
                              "effect":{"objectType":"ODUPath","alarmType":"ODUDown"},
                              "traversal":{"direction":"forward","cardinality":"each-target"}}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is("v1")))
                .andExpect(jsonPath("$.domain", is("transport-otn")));

        // Update → v2; v1 pinned.
        mockMvc.perform(put("/domains/transport-otn/propagation-templates/"
                        + enc("transport-otn/propagationTemplate/CARRIED_OVER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"payload":{
                              "edgeType":"CARRIED_OVER",
                              "trigger":{"objectType":"OCHTrail","alarmType":"OCHDown"},
                              "effect":{"objectType":"ODUPath","alarmType":"ODUDown"},
                              "traversal":{"direction":"forward","cardinality":"each-target"},
                              "ordering":1}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));

        mockMvc.perform(get("/domains/transport-otn/propagation-templates/"
                        + enc("transport-otn/propagationTemplate/CARRIED_OVER") + "/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v1")));

        mockMvc.perform(get("/domains/transport-otn/propagation-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].payload.edgeType", is("CARRIED_OVER")));
    }

    // AC18 — Interface accepted as a core-ip fault-origin type (create/retrieve/update).
    @Test
    void interface_isAcceptedAsCoreIpFaultOriginType() throws Exception {
        SeedFixtures.seedCoreIpVocabularies(mockMvc);

        mockMvc.perform(post("/domains/core-ip/fault-origin-types")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"recordId":"core-ip/faultOriginType/Interface","payload":{
                              "objectType":"Interface","originAlarmType":"InterfaceDown",
                              "description":"L3 interface; originates InterfaceDown"}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.objectType", is("Interface")));

        mockMvc.perform(get("/domains/core-ip/fault-origin-types/"
                        + enc("core-ip/faultOriginType/Interface")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.objectType", is("Interface")));

        mockMvc.perform(put("/domains/core-ip/fault-origin-types/"
                        + enc("core-ip/faultOriginType/Interface"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                            {"payload":{"objectType":"Interface","originAlarmType":"InterfaceDown",
                              "description":"updated"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2")));
    }

    // AC19 — core-ip interface-cascade templates (HOSTS, TERMINATES, ADJACENCY_OVER) created and
    // returned by the domain-scoped list.
    @Test
    void coreIpInterfaceCascadeTemplates_createdAndListed() throws Exception {
        SeedFixtures.seedCoreIpVocabularies(mockMvc);

        // HOSTS: PortDown(Port) => InterfaceDown(each Interface).
        author(mockMvc, "core-ip", "propagation-templates",
                """
                {"recordId":"core-ip/propagationTemplate/HOSTS","payload":{
                  "edgeType":"HOSTS",
                  "trigger":{"objectType":"Port","alarmType":"PortDown"},
                  "effect":{"objectType":"Interface","alarmType":"InterfaceDown"},
                  "traversal":{"direction":"forward","cardinality":"each-target"}}}""");
        // TERMINATES: InterfaceDown(Interface) => LinkDown(its IPLink).
        author(mockMvc, "core-ip", "propagation-templates",
                """
                {"recordId":"core-ip/propagationTemplate/TERMINATES","payload":{
                  "edgeType":"TERMINATES",
                  "trigger":{"objectType":"Interface","alarmType":"InterfaceDown"},
                  "effect":{"objectType":"IPLink","alarmType":"LinkDown"},
                  "traversal":{"direction":"forward","cardinality":"single-target"}}}""");
        // ADJACENCY_OVER: cause is InterfaceDown(Interface), not a Port-originated cause.
        author(mockMvc, "core-ip", "propagation-templates",
                """
                {"recordId":"core-ip/propagationTemplate/ADJACENCY_OVER","payload":{
                  "edgeType":"ADJACENCY_OVER",
                  "trigger":{"objectType":"Interface","alarmType":"InterfaceDown"},
                  "effect":{"objectType":"IGPAdjacency","alarmType":"AdjDown"},
                  "traversal":{"direction":"forward","cardinality":"each-target"}}}""");

        // Retrieve one and verify the InterfaceDown cascade step is persisted faithfully.
        mockMvc.perform(get("/domains/core-ip/propagation-templates/"
                        + enc("core-ip/propagationTemplate/TERMINATES")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.trigger.alarmType", is("InterfaceDown")))
                .andExpect(jsonPath("$.payload.effect.alarmType", is("LinkDown")));

        // All three returned by the domain-scoped list.
        mockMvc.perform(get("/domains/core-ip/propagation-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    private static void author(MockMvc mockMvc, String domain, String segment, String body)
            throws Exception {
        mockMvc.perform(post("/domains/" + domain + "/" + segment)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
