package com.acp.patternmanager.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.TopologyClient;
import com.acp.patternmanager.client.dto.TopologyNode;
import com.acp.patternmanager.client.dto.TraversalResult;
import com.acp.patternmanager.client.dto.TraversalResult.TraversalEdge;
import com.acp.patternmanager.reconcile.CodebookMatch;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RCA graph-ordering + codebook override + vocab-token root cause (criteria 1, 1b, 2). */
@ExtendWith(MockitoExtension.class)
class RcaServiceTest {

    @Mock
    private TopologyClient topologyClient;

    private RcaService rca;

    private final EnrichmentParams params =
            new EnrichmentParams(4, "lenient", "flag", 1.0, 0.5, 0.5);

    @BeforeEach
    void setUp() {
        rca = new RcaService(topologyClient);
    }

    private TopologyNode node(String objectType, String id) {
        return new TopologyNode(objectType + ":" + id, objectType, "core-ip", "s1", id, Map.of());
    }

    // Criterion 1 + 1b: fiber-cut sequence; LOS maps to a FiberSpan with no upstream dependency +
    // earliest position -> rootCauseAlarmType = LOS (an alarmType vocab token, one of the sequence).
    @Test
    void graphOrderingPicksLowestDependencyEarliestTimestamp() {
        List<String> sequence = List.of("LOS", "LinkDown", "AdjDown", "LSPDown");
        // LOS resolves to a FiberSpan with NO upstream (traverse returns no edges pointing into it);
        // the others each have an upstream dependency (an edge whose `to` is themselves).
        when(topologyClient.getNode("LOS")).thenReturn(Optional.of(node("FiberSpan", "1")));
        when(topologyClient.getNode("LinkDown")).thenReturn(Optional.of(node("IPLink", "1")));
        when(topologyClient.getNode("AdjDown")).thenReturn(Optional.of(node("IGPAdjacency", "1")));
        when(topologyClient.getNode("LSPDown")).thenReturn(Optional.of(node("LSP", "1")));

        // FiberSpan:1 -> no incoming edge (depth 0, root). Others have one incoming dependency edge.
        when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), anyInt()))
                .thenReturn(new TraversalResult("FiberSpan:1", 4, List.of(), List.of()));
        when(topologyClient.traverse(eq("IPLink:1"), anyList(), anyInt()))
                .thenReturn(new TraversalResult("IPLink:1", 4, List.of(),
                        List.of(new TraversalEdge("e", "FiberSpan:1", "IPLink:1", "DEPENDS_ON"))));
        when(topologyClient.traverse(eq("IGPAdjacency:1"), anyList(), anyInt()))
                .thenReturn(new TraversalResult("IGPAdjacency:1", 4, List.of(),
                        List.of(new TraversalEdge("e", "IPLink:1", "IGPAdjacency:1", "DEPENDS_ON"))));
        when(topologyClient.traverse(eq("LSP:1"), anyList(), anyInt()))
                .thenReturn(new TraversalResult("LSP:1", 4, List.of(),
                        List.of(new TraversalEdge("e", "IGPAdjacency:1", "LSP:1", "DEPENDS_ON"))));

        RcaResult result = rca.analyze(sequence, params, Optional.empty());

        assertThat(result.rootCauseAlarmType()).isEqualTo("LOS");
        assertThat(sequence).contains(result.rootCauseAlarmType()); // vocab token from the sequence
        assertThat(result.codebookMatchId()).isNull();
        assertThat(result.reconcileStatus()).isEqualTo("unexplained");
        assertThat(result.rootCauseObjectId()).isEqualTo("FiberSpan:1");
    }

    @Test
    void rootCauseAlarmTypeIsAlarmTypeVocabularyToken() {
        List<String> sequence = List.of("LOS", "LinkDown");
        when(topologyClient.getNode("LOS")).thenReturn(Optional.of(node("FiberSpan", "1")));
        when(topologyClient.getNode("LinkDown")).thenReturn(Optional.of(node("IPLink", "1")));
        lenient().when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), anyInt()))
                .thenReturn(new TraversalResult("FiberSpan:1", 4, List.of(), List.of()));
        lenient().when(topologyClient.traverse(eq("IPLink:1"), anyList(), anyInt()))
                .thenReturn(new TraversalResult("IPLink:1", 4, List.of(),
                        List.of(new TraversalEdge("e", "FiberSpan:1", "IPLink:1", "DEPENDS_ON"))));

        RcaResult result = rca.analyze(sequence, params, Optional.empty());
        assertThat(sequence).contains(result.rootCauseAlarmType());
        // Not a probableCause/eventType string.
        assertThat(result.rootCauseAlarmType()).isNotIn("lossOfSignal", "communicationsAlarm");
    }

    // Criterion 2: codebook override replaces the graph RCA and sets codebookMatchId.
    @Test
    void codebookOverrideReplacesGraphRcaAndSetsMatchId() {
        List<String> sequence = List.of("LOS", "LinkDown", "LineCardFault");
        when(topologyClient.getNode("LOS")).thenReturn(Optional.of(node("FiberSpan", "1")));
        when(topologyClient.getNode("LinkDown")).thenReturn(Optional.of(node("IPLink", "1")));
        when(topologyClient.getNode("LineCardFault")).thenReturn(Optional.of(node("LineCard", "1")));
        lenient().when(topologyClient.traverse(org.mockito.ArgumentMatchers.anyString(), anyList(), anyInt()))
                .thenReturn(new TraversalResult("x", 4, List.of(), List.of()));

        CodebookMatch match = new CodebookMatch("scenario-99", "LineCardFault", "confirmed");
        RcaResult result = rca.analyze(sequence, params, Optional.of(match));

        assertThat(result.rootCauseAlarmType()).isEqualTo("LineCardFault");
        assertThat(result.codebookMatchId()).isEqualTo("scenario-99");
        assertThat(result.reconcileStatus()).isEqualTo("confirmed");
    }
}
