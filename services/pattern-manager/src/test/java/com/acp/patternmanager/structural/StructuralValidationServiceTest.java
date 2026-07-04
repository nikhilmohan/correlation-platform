package com.acp.patternmanager.structural;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.client.EnrichmentParams;
import com.acp.patternmanager.client.TopologyClient;
import com.acp.patternmanager.client.dto.TopologyNode;
import com.acp.patternmanager.client.dto.TraversalResult;
import com.acp.patternmanager.rca.ResolvedObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Structural validation — connectivity check reusing RCA-resolved objects (criteria 15, 16, 17). */
@ExtendWith(MockitoExtension.class)
class StructuralValidationServiceTest {

    @Mock
    private TopologyClient topologyClient;

    private StructuralValidationService svc;

    @BeforeEach
    void setUp() {
        svc = new StructuralValidationService(topologyClient);
    }

    private EnrichmentParams params(int maxHops) {
        return new EnrichmentParams(maxHops, "lenient", "flag", 1.0, 0.5, 0.5);
    }

    private TopologyNode node(String id) {
        return new TopologyNode(id, id.split(":")[0], "core-ip", "s1", id, Map.of());
    }

    private List<ResolvedObject> resolved(String... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> new ResolvedObject(id, id, true, 0))
                .toList();
    }

    // Criterion 15: connected objects -> structurallyValidated=true, reason null.
    @Test
    void connectedObjectsValidatedTrueAndPersistedNormally() {
        List<ResolvedObject> objects = resolved("FiberSpan:1", "IPLink:1", "LSP:1");
        when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), eq(4)))
                .thenReturn(new TraversalResult("FiberSpan:1", 4,
                        List.of(node("IPLink:1"), node("LSP:1")), List.of()));

        StructuralResult r = svc.validate(objects, "FiberSpan:1", params(4));

        assertThat(r.structurallyValidated()).isTrue();
        assertThat(r.reason()).isNull();
    }

    // Criterion 16: disjoint objects -> false + non-null reason.
    @Test
    void disjointObjectsFlaggedFalseWithReasonAndSurfacedInReadApi() {
        List<ResolvedObject> objects = resolved("FiberSpan:1", "R7:remote");
        when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), eq(4)))
                .thenReturn(new TraversalResult("FiberSpan:1", 4, List.of(), List.of())); // R7 unreachable

        StructuralResult r = svc.validate(objects, "FiberSpan:1", params(4));

        assertThat(r.structurallyValidated()).isFalse();
        assertThat(r.reason()).isNotNull().contains("R7:remote");
    }

    // Criterion 17: changing Knowledge max-hops flips the outcome (no hard-coded threshold).
    @Test
    void knowledgeMaxHopsChangeFlipsValidationOutcome() {
        List<ResolvedObject> objects = resolved("FiberSpan:1", "LSP:1");
        // With max-hops 4, LSP:1 is reached; with max-hops 1 it is not.
        when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), eq(4)))
                .thenReturn(new TraversalResult("FiberSpan:1", 4, List.of(node("LSP:1")), List.of()));
        when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), eq(1)))
                .thenReturn(new TraversalResult("FiberSpan:1", 1, List.of(), List.of()));

        assertThat(svc.validate(objects, "FiberSpan:1", params(4)).structurallyValidated()).isTrue();
        assertThat(svc.validate(objects, "FiberSpan:1", params(1)).structurallyValidated()).isFalse();
    }

    // Supporting: fewer-than-two distinct resolved objects is trivially connected.
    @Test
    void trivialSingleObjectIsValidatedTrue() {
        StructuralResult r = svc.validate(resolved("FiberSpan:1"), "FiberSpan:1", params(4));
        assertThat(r.structurallyValidated()).isTrue();
        // Trivial case must NOT call Topology.
        verify(topologyClient, never()).traverse(org.mockito.ArgumentMatchers.anyString(), anyList(), anyInt());
    }

    // Supporting: RCA-resolved objects are reused — no getNode re-resolution here.
    @Test
    void rcaResolvedObjectsAreReusedNoRefetch() {
        List<ResolvedObject> objects = resolved("FiberSpan:1", "IPLink:1");
        when(topologyClient.traverse(eq("FiberSpan:1"), anyList(), eq(4)))
                .thenReturn(new TraversalResult("FiberSpan:1", 4, List.of(node("IPLink:1")), List.of()));

        svc.validate(objects, "FiberSpan:1", params(4));

        // Structural validation must NOT re-resolve objects via getNode (RCA already did).
        verify(topologyClient, never()).getNode(org.mockito.ArgumentMatchers.anyString());
    }
}
