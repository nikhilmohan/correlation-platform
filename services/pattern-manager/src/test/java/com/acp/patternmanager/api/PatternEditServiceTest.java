package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.api.dto.PatternEdit;
import com.acp.patternmanager.api.dto.PatternEdit.SequenceFlag;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.error.InvalidLifecycleStateException;
import com.acp.patternmanager.api.error.UnprocessableEditException;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SequenceElementEntity;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Operator edit placeholder: frozen sequenceFlags body (14, 14b), out-of-range 422, non-draft 409. */
@ExtendWith(MockitoExtension.class)
class PatternEditServiceTest {

    @Mock private PatternRepository patternRepository;

    private PatternEditService svc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        svc = new PatternEditService(patternRepository, new PatternViewMapper(mapper), mapper);
    }

    private PatternEntity draftWithSequence() {
        PatternEntity e = new PatternEntity();
        e.setPatternId(UUID.randomUUID());
        e.setTrailId("trail-1");
        e.setRootCauseAlarmType("LOS");
        e.setTimingJson("{}");
        e.setReconcileStatus("unexplained");
        e.setStructurallyValidated(true);
        e.setSessionWindowMs(5000);
        e.setSessionWindowType("gap-based");
        e.setInstanceCount(1);
        e.setLifecycle("draft");
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        e.getSequenceElements().add(new SequenceElementEntity(UUID.randomUUID(), e, 0, "LOS", false));
        e.getSequenceElements().add(new SequenceElementEntity(UUID.randomUUID(), e, 1, "LinkDown", false));
        return e;
    }

    // Criteria 14 + 14b: frozen sequenceFlags body marks a position optional; lifecycle unchanged.
    @Test
    void editDraftMarksOptionalAndRejectsNonDraft() {
        PatternEntity e = draftWithSequence();
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));

        PatternEdit edit = new PatternEdit(List.of(new SequenceFlag(1, true)), "alice", "note");
        PatternView view = svc.applyEdit(e.getPatternId().toString(), edit);

        assertThat(view.lifecycle()).isEqualTo("draft");
        assertThat(view.sequence().get(1).optional()).isTrue();
        assertThat(view.sequence().get(0).optional()).isFalse();

        // The same edit on a non-draft pattern is rejected (409).
        e.setLifecycle("approved");
        assertThatThrownBy(() -> svc.applyEdit(e.getPatternId().toString(), edit))
                .isInstanceOf(InvalidLifecycleStateException.class);
    }

    // Criterion 14b: out-of-range index -> 422.
    @Test
    void patchAcceptsFrozenSequenceFlagsBodyAndRejectsOutOfRangeIndex() {
        PatternEntity e = draftWithSequence();
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));

        PatternEdit bad = new PatternEdit(List.of(new SequenceFlag(9, true)), "alice", null);
        assertThatThrownBy(() -> svc.applyEdit(e.getPatternId().toString(), bad))
                .isInstanceOf(UnprocessableEditException.class);
    }

    // A flag can also CLEAR optional (set-and-clear expressiveness of the frozen shape).
    @Test
    void editCanClearOptional() {
        PatternEntity e = draftWithSequence();
        e.getSequenceElements().get(1).setOptional(true);
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));

        PatternView view = svc.applyEdit(e.getPatternId().toString(),
                new PatternEdit(List.of(new SequenceFlag(1, false)), "alice", null));
        assertThat(view.sequence().get(1).optional()).isFalse();
    }
}
