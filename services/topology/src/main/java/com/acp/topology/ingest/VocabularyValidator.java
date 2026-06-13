package com.acp.topology.ingest;

import com.acp.topology.api.dto.ApiError.Violation;
import com.acp.topology.integration.DomainVocabulary;
import com.acp.topology.integration.KnowledgeVocabClient;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates every node {@code objectType} and every edge {@code relation} against the snapshot
 * {@code domain}'s Knowledge-authored vocabulary (de-frozen, multi-domain). An unknown type/relation
 * for that domain yields 422 (no write, no event). If the vocabulary is unavailable and uncached,
 * the client throws {@code VocabularyUnavailableException} → 502 (fail closed).
 */
@Service
public class VocabularyValidator {

    private static final Logger log = LoggerFactory.getLogger(VocabularyValidator.class);

    private final KnowledgeVocabClient knowledge;

    public VocabularyValidator(KnowledgeVocabClient knowledge) {
        this.knowledge = knowledge;
    }

    /**
     * @throws ValidationException 422 when a type/relation is not in the domain vocabulary
     * @throws com.acp.topology.integration.VocabularyUnavailableException 502 when vocab unavailable
     */
    public void validate(SnapshotFile file) {
        DomainVocabulary vocab = knowledge.getVocabulary(file.domain());
        List<Violation> violations = new ArrayList<>();

        for (int i = 0; i < file.nodes().size(); i++) {
            String type = file.nodes().get(i).objectType();
            if (!vocab.objectTypes().contains(type)) {
                violations.add(new Violation("$.nodes[" + i + "].objectType", "domain-vocabulary",
                        "objectType " + type + " not in domain " + file.domain() + " vocabulary"));
            }
        }
        for (int i = 0; i < file.edges().size(); i++) {
            String relation = file.edges().get(i).relation();
            if (!vocab.relations().contains(relation)) {
                violations.add(new Violation("$.edges[" + i + "].relation", "domain-vocabulary",
                        "relation " + relation + " not in domain " + file.domain() + " vocabulary"));
            }
        }
        if (!violations.isEmpty()) {
            log.warn("snapshot rejected: {} type/relation(s) not in domain {} vocabulary",
                    violations.size(), file.domain());
            throw new ValidationException(
                    "snapshot file uses types/relations not in the domain vocabulary", violations);
        }
    }
}
