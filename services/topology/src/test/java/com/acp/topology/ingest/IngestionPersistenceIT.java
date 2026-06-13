package com.acp.topology.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acp.eventmodel.EventCodec;
import com.acp.topology.TestFixtures;
import com.acp.topology.api.QueryService;
import com.acp.topology.api.dto.SnapshotIngestResponse;
import com.acp.topology.events.DlqPublisher;
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
import com.acp.topology.meta.SnapshotRecord;
import com.acp.topology.meta.SnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
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
 * AC-25 — cross-store persistence split + atomic cut-over + no partial snapshot (design test-plan
 * row 25). Against Testcontainers NebulaGraph + PostgreSQL (Knowledge stubbed, Kafka mocked):
 *
 * <ul>
 *   <li>{@code nebulaWrittenThenPostgresCutOverMakesCurrent} — graph data lands in NebulaGraph and
 *       becomes visible (queryable as {@code current}) only after the PostgreSQL current-pointer
 *       commit.</li>
 *   <li>{@code postgresCutOverFailureLeavesPriorCurrentAndNoVisiblePartial} — a forced PostgreSQL
 *       cut-over failure leaves the prior snapshot {@code current}, the just-written graph data
 *       unreferenced and invisible (no partial snapshot), and the ingest fails (no 200).</li>
 * </ul>
 *
 * Skipped if Docker is absent (graceful JUnit assumption in {@link NebulaIntegrationBase}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionPersistenceIT extends NebulaIntegrationBase {

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource ds;
    private NebulaGraphRepository graphRepo;
    private KnowledgeVocabClient knowledge;
    private KafkaTemplate<String, String> kafka;

    @BeforeAll
    void wire() {
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("correlation").withUsername("correlation").withPassword("correlation");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("topology_meta").defaultSchema("topology_meta").createSchemas(true)
                .locations("classpath:db/migration").load().migrate();

        ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setSchema("topology_meta");
        jdbc = new JdbcTemplate(ds);

        ObjectMapper mapper = new ObjectMapper();
        graphRepo = new NebulaGraphRepository(
                new NebulaSessionProvider(pool, properties),
                new NebulaSchemaBootstrap(pool, properties), properties, mapper);
        graphRepo.bootstrapSchema();

        knowledge = mock(KnowledgeVocabClient.class);
        when(knowledge.getVocabulary("core-ip")).thenReturn(new DomainVocabulary("core-ip",
                Set.of("Node", "LineCard", "Port", "Interface", "IPLink", "IGPAdjacency",
                        "LSP", "VPNService", "FiberSpan", "SRLG", "Site"),
                Set.of("HOSTED_ON", "HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER",
                        "TRAVERSES", "SERVES", "MEMBER_OF", "LOCATED_AT"), "core-ip-v1"));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> k = mock(KafkaTemplate.class);
        when(k.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        kafka = k;
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private SnapshotRepository repo() {
        return new SnapshotRepository(jdbc);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private IngestionService ingestionWith(SnapshotMetadataService metadata) {
        return new IngestionService(
                new SnapshotValidationService(new ObjectMapper()),
                new VocabularyValidator(knowledge),
                metadata,
                new LiftingService(),
                new GraphWriteService(graphRepo),
                new TopologyEventPublisher(kafka, new EventCodec(),
                        new DlqPublisher(kafka, properties), properties));
    }

    @Test
    void nebulaWrittenThenPostgresCutOverMakesCurrent() {
        SnapshotMetadataService metadata =
                new IngestionQueryIT.TxSnapshotMetadataService(repo(), tx());
        IngestionService ingestion = ingestionWith(metadata);
        QueryService query = new QueryService(new GraphReadService(graphRepo), metadata, properties);

        SnapshotIngestResponse resp = ingestion.ingest(
                TestFixtures.snapshot("valid-min.json"), null, "trace-persist-1");

        // The PostgreSQL current pointer committed → the snapshot is current and the graph data is
        // visible by resolving "current" through the metadata pointer.
        assertThat(resp.status()).isEqualTo("current");
        assertThat(metadata.findCurrent("core-ip")).map(SnapshotRecord::snapshotId)
                .hasValue(resp.snapshotId());
        assertThat(query.listNodes(null, "core-ip", "current").nodes()).isNotEmpty();
    }

    @Test
    void postgresCutOverFailureLeavesPriorCurrentAndNoVisiblePartial() {
        // Seed a known-good prior current snapshot.
        SnapshotMetadataService good =
                new IngestionQueryIT.TxSnapshotMetadataService(repo(), tx());
        SnapshotIngestResponse prior = ingestionWith(good).ingest(
                TestFixtures.snapshot("valid-min.json"), null, "trace-persist-prior");
        String priorCurrent = prior.snapshotId();

        // A metadata service whose cut-over commit fails AFTER the NebulaGraph write has happened.
        SnapshotMetadataService failing = new IngestionQueryIT.TxSnapshotMetadataService(repo(), tx()) {
            @Override
            public Optional<String> cutOver(SnapshotRecord newCurrent) {
                throw new IllegalStateException("forced PostgreSQL cut-over failure");
            }
        };

        assertThatThrownBy(() -> ingestionWith(failing).ingest(
                TestFixtures.snapshot("valid-min.json"), null, "trace-persist-fail"))
                .isInstanceOf(IllegalStateException.class);

        // The prior snapshot is STILL current; the failed ingest left no current/visible partial.
        assertThat(good.findCurrent("core-ip")).map(SnapshotRecord::snapshotId)
                .hasValue(priorCurrent);
    }
}
