package com.acp.topology.ingest;

import com.acp.topology.api.dto.SnapshotIngestResponse;
import com.acp.topology.events.TopologyEventPublisher;
import com.acp.topology.graph.GraphWriteService;
import com.acp.topology.meta.SnapshotMetadataService;
import com.acp.topology.meta.SnapshotRecord;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the P1 ingest pipeline (Flow A): validate (schema + semantic) → validate vocab vs
 * Knowledge for the domain → mint/resolve snapshotId → lift → NebulaGraph write (not yet current) →
 * PostgreSQL atomic cut-over (makes it current) → delete evicted prior-previous graph data → emit
 * {@code topology.changed}. A schema-/vocab-invalid file yields no write, no PostgreSQL row, no event.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final SnapshotValidationService validation;
    private final VocabularyValidator vocabulary;
    private final SnapshotMetadataService metadata;
    private final LiftingService lifting;
    private final GraphWriteService graphWrite;
    private final TopologyEventPublisher publisher;

    public IngestionService(SnapshotValidationService validation, VocabularyValidator vocabulary,
            SnapshotMetadataService metadata, LiftingService lifting, GraphWriteService graphWrite,
            TopologyEventPublisher publisher) {
        this.validation = validation;
        this.vocabulary = vocabulary;
        this.metadata = metadata;
        this.lifting = lifting;
        this.graphWrite = graphWrite;
        this.publisher = publisher;
    }

    /**
     * @param rawJson the uploaded snapshot file body
     * @param requestedChangeType optional hint ({@code full-load}|{@code incremental}); may be null
     * @param traceId the request trace id
     */
    public SnapshotIngestResponse ingest(String rawJson, String requestedChangeType, String traceId) {
        // 1. Validate (schema + semantic). Throws ValidationException (422) on failure — no write.
        SnapshotFile file = validation.validate(rawJson);

        // 2. Validate objectTypes/relations vs the domain's Knowledge vocabulary.
        //    Throws ValidationException (422) or VocabularyUnavailableException (502) — no write.
        vocabulary.validate(file);

        // 3. Mint / resolve snapshotId.
        String snapshotId = metadata.resolveSnapshotId(file.snapshotId());
        boolean firstIngest = metadata.isFirstIngest(file.domain());
        String changeType = resolveChangeType(requestedChangeType, firstIngest);

        // 4. Lift.
        LiftingService.Lifted lifted = lifting.lift(file, snapshotId);

        // 5. NebulaGraph write (data present but NOT yet current — invisible to readers).
        graphWrite.writeSnapshot(lifted.vertices(), lifted.edges());

        // 6. PostgreSQL atomic cut-over (the commit point that makes the snapshot current).
        SnapshotRecord record = metadata.build(snapshotId, changeType, file.domain(),
                file.schemaVersion(), lifted.vertices().size(), lifted.edges().size(),
                file.snapshotId(), traceId);
        Optional<String> evicted = metadata.cutOver(record);

        // 7. After the commit: delete the evicted prior-previous graph data.
        evicted.ifPresent(graphWrite::deleteSnapshot);

        // 8. Emit topology.changed (after persist + cut-over succeed).
        String eventId = publisher.emit(snapshotId, file.domain(), changeType,
                lifted.vertices(), lifted.edges(), traceId);
        metadata.recordEventId(snapshotId, eventId);

        log.info("ingest complete snapshotId={} domain={} changeType={} nodes={} edges={}",
                snapshotId, file.domain(), changeType, lifted.vertices().size(),
                lifted.edges().size());

        return new SnapshotIngestResponse(snapshotId, file.domain(), "current",
                lifted.vertices().size(), lifted.edges().size(), changeType);
    }

    /** First ingest is always full-load (AC-15); otherwise honour the hint, default full-load. */
    private String resolveChangeType(String requested, boolean firstIngest) {
        if (firstIngest) {
            return "full-load";
        }
        if ("incremental".equals(requested)) {
            return "incremental";
        }
        return "full-load";
    }
}
