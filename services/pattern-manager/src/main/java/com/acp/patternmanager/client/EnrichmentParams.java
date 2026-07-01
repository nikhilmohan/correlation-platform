package com.acp.patternmanager.client;

/**
 * Typed RCA / structural-validation / reconciliation parameters resolved from the Knowledge
 * Service model-params record ({@code core-ip/modelParams/pattern-manager}). NO threshold is
 * hard-coded in the service — every value here comes from Knowledge (criterion 17). Flattened from
 * the Knowledge {@code RecordResponse} envelope's {@code payload.params[]} list of dotted-key
 * {@code {key,value}} entries.
 *
 * @param structuralMaxHops max traversal hops to consider two objects connected ({@code structural.maxHops})
 * @param structuralStrictness {@code lenient} (undirected, MVP default) or {@code strict} (directed)
 *     ({@code structural.strictness})
 * @param structuralFlagVsReject {@code flag} (MVP) or {@code reject} (post-MVP) ({@code structural.flagVsReject})
 * @param rcaDependencyOrderingWeight weight for dependency-ordering in RCA ({@code rca.dependencyOrderingWeight})
 * @param rcaTimestampWeight weight for earliest-timestamp tie-break ({@code rca.timestampWeight})
 * @param reconciliationOverlapThreshold min sequence/scenario overlap to count as a match
 *     ({@code reconciliation.overlapThreshold})
 */
public record EnrichmentParams(
        int structuralMaxHops,
        String structuralStrictness,
        String structuralFlagVsReject,
        double rcaDependencyOrderingWeight,
        double rcaTimestampWeight,
        double reconciliationOverlapThreshold) {

    /** @return {@code true} when structural traversal is directed (strict), else undirected. */
    public boolean structuralDirected() {
        return "strict".equalsIgnoreCase(structuralStrictness);
    }
}
