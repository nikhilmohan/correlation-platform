package com.acp.topology.integration;

import java.util.Set;

/**
 * A domain's Knowledge-authored vocabulary: the object-type set + relation set, plus a version.
 * Frozen Knowledge shape: {@code GET /domains/{domain}/vocabulary} →
 * {@code { domain, objectTypes[], relations[], version }}.
 */
public record DomainVocabulary(
        String domain,
        Set<String> objectTypes,
        Set<String> relations,
        String version) {
}
