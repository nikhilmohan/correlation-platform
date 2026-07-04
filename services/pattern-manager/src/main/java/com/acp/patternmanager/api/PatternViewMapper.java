package com.acp.patternmanager.api;

import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SampleAlarmView;
import com.acp.patternmanager.api.dto.SequenceElementView;
import com.acp.patternmanager.api.dto.SessionWindowView;
import com.acp.patternmanager.api.dto.SupportingInstanceView;
import com.acp.patternmanager.naming.PatternNaming;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SampleAlarmEntity;
import com.acp.patternmanager.store.entity.SupportingInstanceEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a persisted {@link PatternEntity} to the frozen {@link PatternView} — carrying {@code trailId}
 * (P3-G1), the vocab-token {@code rootCauseAlarmType} (P2-GAP-04), the derived {@code sessionWindow},
 * and the structural-validation flag/reason for the web-ui XAI view.
 */
@Component
public class PatternViewMapper {

    private final ObjectMapper objectMapper;

    public PatternViewMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PatternView toView(PatternEntity e) {
        List<SequenceElementView> seq = e.getSequenceElements().stream()
                .map(se -> new SequenceElementView(se.getAlarmType(), se.isOptional()))
                .toList();
        List<SupportingInstanceView> instances = e.getSupportingInstances().stream()
                .map(this::toInstance)
                .toList();
        // [SAMPLE-ALARMS] Ordered by position (entity @OrderBy); always non-null (empty [] when none).
        List<SampleAlarmView> sampleAlarms = e.getSampleAlarms().stream()
                .map(this::toSampleAlarm)
                .toList();
        // Read the PERSISTED name (the DB is the SSoT). Defensive fallback ONLY if the column is
        // somehow null (e.g. a row that predates the backfill) so the API never serves a null name.
        String patternName = e.getPatternName() != null
                ? e.getPatternName()
                : PatternNaming.patternName(e.getRootCauseAlarmType(), e.getPatternId().toString());
        return new PatternView(
                e.getPatternId().toString(),
                patternName,
                e.getTrailId(),
                seq,
                e.getRootCauseAlarmType(),
                e.getSupport(),
                e.getConfidence(),
                e.getLift(),
                readJson(e.getTimingJson()),
                new SessionWindowView(e.getSessionWindowMs(), e.getSessionWindowType()),
                e.getCodebookMatchId(),
                e.getReconcileStatus(),
                e.isStructurallyValidated(),
                e.getStructuralValidationReason(),
                e.getInstanceCount(),
                e.getOccurrenceCount(),
                e.getTrailCount(),
                e.getFirstSeen(),
                e.getLastSeen(),
                instances,
                sampleAlarms,
                e.getLifecycle(),
                e.getDomain(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private SupportingInstanceView toInstance(SupportingInstanceEntity si) {
        return new SupportingInstanceView(si.getSourceWindowId(), si.getSnapshotId(),
                readJson(si.getOccurrence()));
    }

    private SampleAlarmView toSampleAlarm(SampleAlarmEntity sa) {
        return new SampleAlarmView(sa.getAlarmId(), sa.getAlarmType(), sa.getRaisedAt(),
                sa.getManagedObjectId(), sa.getPerceivedSeverity());
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read JSON column", e);
        }
    }
}
