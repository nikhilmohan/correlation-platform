package com.acp.topology.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acp.eventmodel.EventCodec;
import com.acp.topology.TestFixtures;
import com.acp.topology.api.QueryService;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.SiteListDto;
import com.acp.topology.api.dto.SiteObjectsDto;
import com.acp.topology.api.dto.SnapshotIngestResponse;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.events.TopologyEventPublisher;
import com.acp.topology.graph.GraphReadService;
import com.acp.topology.graph.GraphWriteService;
import com.acp.topology.graph.NebulaGraphRepository;
import com.acp.topology.graph.NebulaIntegrationBase;
import com.acp.topology.graph.NebulaSchemaBootstrap;
import com.acp.topology.graph.NebulaSessionProvider;
import com.acp.topology.integration.DomainVocabulary;
import com.acp.topology.integration.KnowledgeVocabClient;
import com.acp.topology.meta.SnapshotMetadataService;
import com.acp.topology.meta.SnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * End-to-end ingest → query path against Testcontainers NebulaGraph + PostgreSQL (Knowledge stubbed,
 * Kafka mocked): AC-1 (load + queryable), AC-8 (producer-supplied snapshotId flows to the response),
 * AC-10/20 (all types + attributes round-trip), AC-14 (re-ingest mints a new id, both listed),
 * AC-22 (Site + LOCATED_AT lift + frozen site query shapes), AC-25 (cross-store cut-over makes the
 * snapshot current + queryable). Skipped if Docker absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionQueryIT extends NebulaIntegrationBase {

    private PostgreSQLContainer<?> postgres;
    private IngestionService ingestion;
    private QueryService query;
    private SnapshotMetadataService metadata;

    @BeforeAll
    void wire() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("correlation").withUsername("correlation").withPassword("correlation");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("topology_meta").defaultSchema("topology_meta").createSchemas(true)
                .locations("classpath:db/migration").load().migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setSchema("topology_meta");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        SnapshotRepository snapshotRepo = new SnapshotRepository(jdbc);
        metadata = new TxSnapshotMetadataService(snapshotRepo,
                new TransactionTemplate(new DataSourceTransactionManager(ds)));

        ObjectMapper mapper = new ObjectMapper();
        NebulaGraphRepository graphRepo = new NebulaGraphRepository(
                new NebulaSessionProvider(pool, properties),
                new NebulaSchemaBootstrap(pool, properties), properties, mapper);
        graphRepo.bootstrapSchema();

        KnowledgeVocabClient knowledge = mock(KnowledgeVocabClient.class);
        when(knowledge.getVocabulary("core-ip")).thenReturn(new DomainVocabulary("core-ip",
                Set.of("Node", "LineCard", "Port", "Interface", "IPLink", "IGPAdjacency",
                        "LSP", "VPNService", "FiberSpan", "SRLG", "Site"),
                Set.of("HOSTED_ON", "HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER",
                        "TRAVERSES", "SERVES", "MEMBER_OF", "LOCATED_AT"), "core-ip-v1"));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        ingestion = new IngestionService(
                new SnapshotValidationService(mapper),
                new VocabularyValidator(knowledge),
                metadata,
                new LiftingService(),
                new GraphWriteService(graphRepo),
                new TopologyEventPublisher(kafka, new EventCodec(),
                        new com.acp.topology.events.DlqPublisher(kafka, properties), properties));
        query = new QueryService(new GraphReadService(graphRepo), metadata, properties);
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void suppliedIdFlowsToResponseAndAllTypesQueryable() {
        SnapshotIngestResponse resp = ingestion.ingest(
                TestFixtures.snapshot("valid-all-core-ip-types.json"), null, "trace-it-1");
        assertThat(resp.snapshotId()).isEqualTo("SNAP-ALL-TYPES-001"); // producer-supplied (AC-8)
        assertThat(resp.status()).isEqualTo("current");
        assertThat(resp.nodeCount()).isEqualTo(11);

        // AC-1/AC-10: nodes queryable with correct objectType; AC-20: attributes round-trip.
        NodeDto node = query.getNode("Node:PE1", "core-ip", "current");
        assertThat(node.objectType()).isEqualTo("Node");
        assertThat(node.attributes()).containsEntry("vendor", "acme");

        // AC-22: Site + LOCATED_AT lift; frozen site shapes (flat geo + nodes AND edges).
        SiteListDto sites = query.listSites("core-ip", "current");
        assertThat(sites.sites()).extracting(s -> s.siteId()).contains("Site:LON-DC1");
        assertThat(sites.sites().get(0).latitude()).isEqualTo(51.5);

        SiteObjectsDto objects = query.objectsAtSite("Site:LON-DC1", "core-ip", "current");
        assertThat(objects.nodes()).extracting(NodeDto::managedObjectId).contains("Node:PE1");
        assertThat(objects.edges()).isNotEmpty();

        // Regression (P1 sites/{siteId}/objects 404): every siteId the list endpoint emits MUST
        // resolve via objectsAtSite (round-trip-consistent). The list↔objects contract used to break
        // because getNode issued a `LOOKUP … id(vertex) == …` predicate NebulaGraph rejects.
        for (var site : sites.sites()) {
            SiteObjectsDto roundTrip = query.objectsAtSite(site.siteId(), "core-ip", "current");
            assertThat(roundTrip.siteId()).isEqualTo(site.siteId());
        }
        // The seeded London DC has at least one device hanging off it (LOCATED_AT).
        assertThat(objects.nodes()).isNotEmpty();
    }

    @Test
    void reingestMintsNewSnapshotIdAndBothListed() {
        // First ingest of a minted-id file.
        SnapshotIngestResponse first = ingestion.ingest(
                TestFixtures.snapshot("valid-min.json"), null, "trace-it-2");
        SnapshotIngestResponse second = ingestion.ingest(
                TestFixtures.snapshot("valid-min.json"), null, "trace-it-3");
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId()); // AC-14
        assertThat(metadata.listByDomain("core-ip")).extracting(r -> r.snapshotId())
                .contains(first.snapshotId(), second.snapshotId());
    }

    /** Wraps the cut-over in a real transaction (the manual wiring has no @Transactional proxy). */
    static class TxSnapshotMetadataService extends SnapshotMetadataService {
        private final TransactionTemplate tx;
        private final SnapshotRepository repo;

        TxSnapshotMetadataService(SnapshotRepository repo, TransactionTemplate tx) {
            super(repo);
            this.repo = repo;
            this.tx = tx;
        }

        @Override
        public java.util.Optional<String> cutOver(com.acp.topology.meta.SnapshotRecord newCurrent) {
            return tx.execute(s -> {
                java.util.Optional<String> evicted = repo.findPreviousSnapshotId(newCurrent.domain());
                evicted.ifPresent(repo::deleteBySnapshotId);
                repo.demoteCurrentToPrevious(newCurrent.domain());
                repo.insert(newCurrent);
                return evicted;
            });
        }
    }
}
