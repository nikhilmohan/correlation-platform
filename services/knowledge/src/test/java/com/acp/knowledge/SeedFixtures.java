package com.acp.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test helper: author the minimal core-ip vocabularies (object-type, edge-relation, alarm-type)
 * so cross-record reference validation of templates/fault-origins/trail-policy passes in tests
 * that exercise those types. Uses the public API (the same validated write path).
 */
final class SeedFixtures {

    private SeedFixtures() {
    }

    static void seedCoreIpVocabularies(MockMvc mockMvc) throws Exception {
        author(mockMvc, "object-type-vocabulary",
                """
                {"recordId":"core-ip/objectTypeVocabulary/default","payload":{
                  "objectTypes":["Node","LineCard","Port","Interface","IPLink","IGPAdjacency",
                    "LSP","VPNService","FiberSpan","SRLG","Site"]}}""");
        author(mockMvc, "edge-relation-vocabulary",
                """
                {"recordId":"core-ip/edgeRelationVocabulary/default","payload":{
                  "relations":["HOSTED_ON","HOSTS","TERMINATES","RIDES_ON","ADJACENCY_OVER",
                    "TRAVERSES","SERVES","MEMBER_OF","LOCATED_AT"]}}""");
        author(mockMvc, "alarm-type-vocabulary",
                """
                {"recordId":"core-ip/alarmTypeVocabulary/default","payload":{
                  "alarmTypes":["LOS","LOF","OpticalPowerLow","FiberCut","FiberFault",
                    "PortDown","LineCardFault","CRCErrors","PortFlapping","LinkBundleDegraded",
                    "InterfaceDown","InterfaceErrors","IPLinkDown","LinkDown",
                    "ISISAdjacencyDown","AdjDown","OSPFAdjacencyDown","BGPPeerDown","RouteFlap","LDPSessionDown",
                    "LSPDown","FRRSwitchover","TETunnelDown",
                    "VPNReachabilityLoss","ReachabilityLoss","ServiceDegraded",
                    "Congestion","QueueDrop","HighLatency"]}}""");
    }

    private static void author(MockMvc mockMvc, String segment, String body) throws Exception {
        mockMvc.perform(post("/domains/core-ip/" + segment)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
