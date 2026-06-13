package com.acp.knowledge.domain;

import com.acp.knowledge.store.RecordStore;
import com.acp.knowledge.validation.ValidationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the validate → version → emit write path and the read paths for all eight record
 * types (one identical path, the unified record model).
 *
 * <p>Write flow (per the design's algorithm): validate (no partial write) → delegate to
 * {@link VersioningService} which commits the new version in one transaction and emits
 * {@code knowledge.updated} after commit.
 */
@Service
public class RecordService {

    private final ValidationService validation;
    private final VersioningService versioning;
    private final RecordStore store;

    public RecordService(ValidationService validation, VersioningService versioning,
            RecordStore store) {
        this.validation = validation;
        this.versioning = versioning;
        this.store = store;
    }

    /** Create a new record (initial version {@code v1}); a create on an existing record versions. */
    public KnowledgeRecord create(String domain, RecordType type, String recordId,
            JsonNode payload, String author) {
        validation.validate(domain, type, payload);
        String version;
        if (store.recordExists(domain, type.id(), recordId)) {
            version = versioning.writeNewVersion(domain, type.id(), recordId, payload, author);
        } else {
            version = versioning.writeFirstVersion(domain, type.id(), recordId, payload, author);
        }
        return version(domain, type, recordId, version);
    }

    /** Update an existing record (mints {@code v{n+1}}); 404 if the record does not exist. */
    public KnowledgeRecord update(String domain, RecordType type, String recordId,
            JsonNode payload, String author) {
        validation.validate(domain, type, payload);
        if (!store.recordExists(domain, type.id(), recordId)) {
            throw new NotFoundException("no " + type.id() + " record " + recordId
                    + " in domain " + domain);
        }
        String version = versioning.writeNewVersion(domain, type.id(), recordId, payload, author);
        return version(domain, type, recordId, version);
    }

    /** @return the current version of a record (404 if absent). */
    public KnowledgeRecord current(String domain, RecordType type, String recordId) {
        return store.findCurrent(domain, type.id(), recordId)
                .orElseThrow(() -> new NotFoundException("no current " + type.id() + " record "
                        + recordId + " in domain " + domain));
    }

    /** @return a pinned version of a record (404 if absent). */
    public KnowledgeRecord version(String domain, RecordType type, String recordId,
            String version) {
        return store.findVersion(domain, type.id(), recordId, version)
                .orElseThrow(() -> new NotFoundException("no " + type.id() + " record " + recordId
                        + " version " + version + " in domain " + domain));
    }

    /** @return all current records for a domain + type. */
    public List<KnowledgeRecord> list(String domain, RecordType type) {
        return store.listCurrent(domain, type.id());
    }
}
