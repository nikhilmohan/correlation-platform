package com.acp.topology.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.acp.topology.TestFixtures;
import com.acp.topology.integration.DomainVocabulary;
import com.acp.topology.integration.KnowledgeVocabClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** AC-7 / AC-7b / AC-23 / AC-24 (vocab part): objectType/relation validated vs Knowledge vocab. */
@ExtendWith(MockitoExtension.class)
class VocabularyValidatorTest {

    @Mock
    private KnowledgeVocabClient knowledge;

    private VocabularyValidator validator;
    private SnapshotValidationService structural;

    private static final DomainVocabulary CORE_IP = new DomainVocabulary("core-ip",
            Set.of("Node", "LineCard", "Port", "Interface", "IPLink", "IGPAdjacency",
                    "LSP", "VPNService", "FiberSpan", "SRLG", "Site"),
            Set.of("HOSTED_ON", "HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER",
                    "TRAVERSES", "SERVES", "MEMBER_OF", "LOCATED_AT"),
            "core-ip-v1");

    @BeforeEach
    void setUp() {
        validator = new VocabularyValidator(knowledge);
        structural = new SnapshotValidationService(new ObjectMapper());
    }

    @Test
    void acceptsTypesAndRelationsInDomainVocab_rejectsOthers() {
        when(knowledge.getVocabulary("core-ip")).thenReturn(CORE_IP);
        SnapshotFile valid = structural.validate(
                TestFixtures.snapshot("valid-all-core-ip-types.json"));
        assertThatCode(() -> validator.validate(valid)).doesNotThrowAnyException();
    }

    @Test
    void rejectsObjectTypeNotInDomainVocabulary() {
        when(knowledge.getVocabulary("core-ip")).thenReturn(CORE_IP);
        SnapshotFile file = structural.validate(
                TestFixtures.snapshot("unknown-objecttype-for-domain.json"));
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .anyMatch(v -> v.rule().equals("domain-vocabulary")
                                && v.path().contains("objectType")));
    }

    @Test
    void rejectsRelationNotInDomainVocabulary() {
        when(knowledge.getVocabulary("core-ip")).thenReturn(CORE_IP);
        SnapshotFile file = structural.validate(
                TestFixtures.snapshot("unknown-relation-for-domain.json"));
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getViolations())
                        .anyMatch(v -> v.rule().equals("domain-vocabulary")
                                && v.path().contains("relation")));
    }

    @Test
    void acceptsInterfaceHostsTerminatesInCoreIpVocab() {
        when(knowledge.getVocabulary("core-ip")).thenReturn(CORE_IP);
        SnapshotFile file = structural.validate(TestFixtures.snapshot("with-interfaces.json"));
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }
}
