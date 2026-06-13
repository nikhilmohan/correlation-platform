package com.acp.knowledge.api;

import com.acp.knowledge.api.dto.CreateRecordRequest;
import com.acp.knowledge.api.dto.RecordResponse;
import com.acp.knowledge.api.dto.UpdateRecordRequest;
import com.acp.knowledge.domain.RecordService;
import com.acp.knowledge.domain.RecordType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic CRUD + versioned read for all eight {@code recordType}s. Routes are domain-scoped and
 * {@code recordType}-generic: {@code /domains/{domain}/{recordType}}. {@code {recordType}} is a
 * kebab-case path segment for one of the eight types (e.g. {@code propagation-templates}).
 *
 * <p>One identical CRUD/versioning path for every type (the unified record model) — adding a
 * recordType is a schema resource + a {@link RecordType} entry, never a new controller.
 */
@RestController
@RequestMapping("/domains/{domain}")
@Tag(name = "knowledge-records",
        description = "CRUD + versioned read for the eight knowledge record types")
public class RecordController {

    private final RecordService records;

    public RecordController(RecordService records) {
        this.records = records;
    }

    @Operation(summary = "Create a knowledge record (initial version v1)")
    @PostMapping("/{recordType}")
    public ResponseEntity<RecordResponse> create(
            @PathVariable String domain,
            @PathVariable String recordType,
            @RequestBody CreateRecordRequest body) {
        RecordType type = resolve(recordType);
        var record = records.create(domain, type, body.recordId(), body.payload(), body.author());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecordResponse.from(record));
    }

    @Operation(summary = "Update a knowledge record (mints a new version)")
    @PutMapping("/{recordType}/{recordId}")
    public ResponseEntity<RecordResponse> update(
            @PathVariable String domain,
            @PathVariable String recordType,
            @PathVariable String recordId,
            @RequestBody UpdateRecordRequest body) {
        RecordType type = resolve(recordType);
        var record = records.update(domain, type, decode(recordId), body.payload(), body.author());
        return ResponseEntity.ok(RecordResponse.from(record));
    }

    @Operation(summary = "List the current records for a domain + record type")
    @GetMapping("/{recordType}")
    public List<RecordResponse> list(
            @PathVariable String domain,
            @PathVariable String recordType,
            @RequestParam(name = "recordId", required = false) String recordId) {
        RecordType type = resolve(recordType);
        if (recordId != null) {
            return List.of(RecordResponse.from(records.current(domain, type, decode(recordId))));
        }
        return records.list(domain, type).stream().map(RecordResponse::from).toList();
    }

    @Operation(summary = "Get the current version of a record")
    @GetMapping("/{recordType}/{recordId}")
    public RecordResponse current(
            @PathVariable String domain,
            @PathVariable String recordType,
            @PathVariable String recordId) {
        RecordType type = resolve(recordType);
        return RecordResponse.from(records.current(domain, type, decode(recordId)));
    }

    @Operation(summary = "Get a pinned version of a record (version pinning)")
    @GetMapping("/{recordType}/{recordId}/versions/{version}")
    public RecordResponse pinned(
            @PathVariable String domain,
            @PathVariable String recordType,
            @PathVariable String recordId,
            @PathVariable String version) {
        RecordType type = resolve(recordType);
        return RecordResponse.from(records.version(domain, type, decode(recordId), version));
    }

    private static RecordType resolve(String recordType) {
        return RecordType.byPathSegment(recordType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown recordType path segment: " + recordType));
    }

    /**
     * Decode a path-variable recordId. URL path matching runs without decoding (see
     * {@code WebConfig}) so slash-bearing recordIds stay in one segment; we decode once here.
     */
    private static String decode(String recordId) {
        return java.net.URLDecoder.decode(recordId, java.nio.charset.StandardCharsets.UTF_8);
    }
}
