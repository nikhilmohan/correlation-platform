package com.acp.patternmanager.api;

import com.acp.patternmanager.api.dto.PatternEdit;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.error.InvalidLifecycleStateException;
import com.acp.patternmanager.api.error.PatternNotFoundException;
import com.acp.patternmanager.api.error.UnprocessableEditException;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SequenceElementEntity;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies operator edits to a {@code draft} pattern (design task 12, placeholder). Sets per-position
 * {@code optional} markers from the frozen {@link PatternEdit} body and stores reviewer/notes into
 * the internal {@code edit_meta}; returns the updated record. Edit metadata is internal — never on
 * the frozen events. {@code sessionWindow} is NOT editable (read-only, OQ-6). Out-of-range index
 * -> 422; non-draft -> 409.
 */
@Service
public class PatternEditService {

    private static final Logger log = LoggerFactory.getLogger(PatternEditService.class);

    private final PatternRepository patternRepository;
    private final PatternViewMapper mapper;
    private final ObjectMapper objectMapper;

    public PatternEditService(PatternRepository patternRepository, PatternViewMapper mapper,
            ObjectMapper objectMapper) {
        this.patternRepository = patternRepository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Apply the edit to a draft pattern.
     *
     * @param patternId the pattern id
     * @param edit the frozen edit body
     * @return the updated pattern view
     */
    @Transactional
    public PatternView applyEdit(String patternId, PatternEdit edit) {
        PatternEntity entity = load(patternId);
        if (!"draft".equals(entity.getLifecycle())) {
            throw new InvalidLifecycleStateException(patternId,
                    "pattern " + patternId + " is in state '" + entity.getLifecycle()
                            + "', edits allowed only in draft");
        }

        List<SequenceElementEntity> elements = entity.getSequenceElements();
        int size = elements.size();
        Map<Integer, SequenceElementEntity> byPosition = new LinkedHashMap<>();
        elements.forEach(el -> byPosition.put(el.getPosition(), el));

        for (PatternEdit.SequenceFlag flag : edit.sequenceFlags()) {
            int index = flag.index();
            if (index < 0 || index >= size) {
                throw new UnprocessableEditException(patternId,
                        "sequenceFlags index " + index + " out of range [0," + (size - 1) + "]");
            }
            byPosition.get(index).setOptional(Boolean.TRUE.equals(flag.optional()));
        }

        entity.setEditMeta(writeEditMeta(edit));
        entity.setUpdatedAt(OffsetDateTime.now());
        patternRepository.save(entity);
        log.info("applied edit to draft pattern {} ({} flags) by {}", patternId,
                edit.sequenceFlags().size(), edit.reviewer());
        return mapper.toView(entity);
    }

    private String writeEditMeta(PatternEdit edit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reviewer", edit.reviewer());
        meta.put("notes", edit.notes());
        meta.put("editedAt", OffsetDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize edit_meta", e);
        }
    }

    private PatternEntity load(String patternId) {
        UUID id;
        try {
            id = UUID.fromString(patternId);
        } catch (IllegalArgumentException e) {
            throw new PatternNotFoundException(patternId);
        }
        return patternRepository.findById(id)
                .orElseThrow(() -> new PatternNotFoundException(patternId));
    }
}
