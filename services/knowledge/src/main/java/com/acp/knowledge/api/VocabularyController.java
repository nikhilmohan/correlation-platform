package com.acp.knowledge.api;

import com.acp.knowledge.api.dto.VocabularyResponse;
import com.acp.knowledge.domain.KnowledgeRecord;
import com.acp.knowledge.domain.NotFoundException;
import com.acp.knowledge.domain.RecordType;
import com.acp.knowledge.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dedicated single-call vocabulary query for the Topology Service (FROZEN P1-G11):
 * {@code GET /domains/{domain}/vocabulary} → {@code {domain, objectTypes[], relations[], version}}.
 * Both current vocabulary sets in one response. 404 for an unknown domain.
 *
 * <p>This is the single source Topology validates an uploaded snapshot's tokens against before
 * lifting it into the graph.
 */
@RestController
@Tag(name = "vocabulary", description = "Domain vocabulary query for Topology snapshot validation")
public class VocabularyController {

    private final RecordStore store;

    public VocabularyController(RecordStore store) {
        this.store = store;
    }

    @Operation(summary = "Fetch the domain's object-type and edge-relation sets (one call)")
    @GetMapping("/domains/{domain}/vocabulary")
    public VocabularyResponse vocabulary(@PathVariable String domain) {
        if (!store.domainExists(domain)) {
            throw new NotFoundException("unknown domain: " + domain);
        }
        List<String> objectTypes = tokens(domain, RecordType.OBJECT_TYPE_VOCABULARY, "objectTypes");
        List<String> relations = tokens(domain, RecordType.EDGE_RELATION_VOCABULARY, "relations");
        String version = currentVersionMarker(domain);
        return new VocabularyResponse(domain, objectTypes, relations, version);
    }

    private List<String> tokens(String domain, RecordType type, String arrayField) {
        List<String> out = new ArrayList<>();
        for (KnowledgeRecord r : store.listCurrent(domain, type.id())) {
            JsonNode arr = r.payload().path(arrayField);
            if (arr.isArray()) {
                arr.forEach(n -> out.add(n.asText()));
            }
        }
        return out;
    }

    /**
     * The opaque current-read marker. When the two vocabulary records share a version, that
     * version is reported; otherwise a composite marker is returned.
     */
    private String currentVersionMarker(String domain) {
        String objVersion = singleVersion(domain, RecordType.OBJECT_TYPE_VOCABULARY);
        String relVersion = singleVersion(domain, RecordType.EDGE_RELATION_VOCABULARY);
        if (objVersion != null && objVersion.equals(relVersion)) {
            return objVersion;
        }
        return "objectTypes=" + objVersion + ";relations=" + relVersion;
    }

    private String singleVersion(String domain, RecordType type) {
        List<KnowledgeRecord> rows = store.listCurrent(domain, type.id());
        return rows.isEmpty() ? null : rows.get(0).version();
    }
}
