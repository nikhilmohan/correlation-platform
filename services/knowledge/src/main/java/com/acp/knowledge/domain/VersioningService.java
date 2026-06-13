package com.acp.knowledge.domain;

import com.acp.knowledge.kafka.KnowledgeUpdatedPublisher;
import com.acp.knowledge.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the atomic version commit + post-commit publish. A separate bean from
 * {@link RecordService} so the {@code @Transactional} boundary is honoured (Spring proxies
 * cross-bean calls). Validation runs in {@link RecordService} BEFORE these methods are called,
 * so the transaction is never opened for an invalid write (no partial write).
 */
@Service
public class VersioningService {

    private final RecordStore store;
    private final ObjectProvider<KnowledgeUpdatedPublisher> publisherProvider;
    private final MeterRegistry registry;

    public VersioningService(RecordStore store,
            ObjectProvider<KnowledgeUpdatedPublisher> publisherProvider, MeterRegistry registry) {
        this.store = store;
        this.publisherProvider = publisherProvider;
        this.registry = registry;
    }

    /** Insert identity + {@code v1} in one transaction, then publish after commit. */
    @Transactional
    public String writeFirstVersion(String domain, String recordType, String recordId,
            JsonNode payload, String author) {
        store.insertIdentity(domain, recordType, recordId);
        store.insertVersion(domain, recordType, recordId, "v1", payload, author);
        countWrite(recordType, "created");
        schedulePublish(domain, recordType, recordId, "v1");
        return "v1";
    }

    /** Flip prior current + insert {@code v{n+1}} in one transaction, then publish after commit. */
    @Transactional
    public String writeNewVersion(String domain, String recordType, String recordId,
            JsonNode payload, String author) {
        int next = store.maxVersionNumber(domain, recordType, recordId) + 1;
        String version = "v" + next;
        store.clearCurrent(domain, recordType, recordId);
        store.insertVersion(domain, recordType, recordId, version, payload, author);
        countWrite(recordType, "updated");
        schedulePublish(domain, recordType, recordId, version);
        return version;
    }

    private void countWrite(String recordType, String result) {
        registry.counter("knowledge_writes_total", "recordType", recordType, "result", result)
                .increment();
    }

    private void schedulePublish(String domain, String recordType, String recordId,
            String version) {
        KnowledgeUpdatedPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            return; // producer disabled (slice tests)
        }
        String eventId = UUID.randomUUID().toString();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publisher.publishWithEventId(eventId, domain, recordType, recordId,
                                    version);
                        }
                    });
        } else {
            publisher.publishWithEventId(eventId, domain, recordType, recordId, version);
        }
    }
}
