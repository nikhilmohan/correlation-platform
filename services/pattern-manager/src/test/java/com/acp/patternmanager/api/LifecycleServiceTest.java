package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.api.dto.ApprovalIntent;
import com.acp.patternmanager.api.dto.DeprecateIntent;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.error.InvalidLifecycleStateException;
import com.acp.patternmanager.event.PatternEventPublisher;
import com.acp.patternmanager.store.entity.LifecycleTransitionEntity;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.LifecycleTransitionRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Lifecycle transitions: approve (7), reject terminal no-event (22), deprecate (9). */
@ExtendWith(MockitoExtension.class)
class LifecycleServiceTest {

    @Mock private PatternRepository patternRepository;
    @Mock private LifecycleTransitionRepository transitionRepository;
    @Mock private PatternEventPublisher eventPublisher;

    private LifecycleService svc;
    private final PatternViewMapper mapper =
            new PatternViewMapper(new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeEach
    void setUp() {
        svc = new LifecycleService(patternRepository, transitionRepository, eventPublisher, mapper);
    }

    private PatternEntity draft() {
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
        return e;
    }

    // Criterion 7: approve draft -> approved + exactly one PatternApprovedEvent.
    @Test
    void approveTransitionsToApprovedAndEmitsExactlyOneEvent() {
        PatternEntity e = draft();
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));

        PatternView view = svc.decide(e.getPatternId().toString(),
                new ApprovalIntent("approve", "alice", "looks good"), "trace-1");

        assertThat(view.lifecycle()).isEqualTo("approved");
        assertThat(e.getLifecycle()).isEqualTo("approved");
        verify(eventPublisher, times(1)).publishApproved(any(PatternEntity.class), anyString());
        ArgumentCaptor<LifecycleTransitionEntity> cap =
                ArgumentCaptor.forClass(LifecycleTransitionEntity.class);
        verify(transitionRepository).save(cap.capture());
        assertThat(cap.getValue().getFromState()).isEqualTo("draft");
        assertThat(cap.getValue().getToState()).isEqualTo("approved");
        assertThat(cap.getValue().getTransitionedAt()).isNotNull();
    }

    // Criterion 22: reject draft -> rejected (terminal) + audit + NO event; then terminal 409.
    @Test
    void rejectTransitionsToRejectedTerminalNoEventNotServed() {
        PatternEntity e = draft();
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));

        PatternView view = svc.decide(e.getPatternId().toString(),
                new ApprovalIntent("reject", "bob", "artifact"), "trace-2");

        assertThat(view.lifecycle()).isEqualTo("rejected");
        assertThat(e.getLifecycle()).isEqualTo("rejected");
        verify(eventPublisher, never()).publishApproved(any(), anyString());
        ArgumentCaptor<LifecycleTransitionEntity> cap =
                ArgumentCaptor.forClass(LifecycleTransitionEntity.class);
        verify(transitionRepository).save(cap.capture());
        assertThat(cap.getValue().getToState()).isEqualTo("rejected");
        assertThat(cap.getValue().getTransitionedAt()).isNotNull();

        // A subsequent approve/reject/deprecate on the now-rejected pattern is 409 (terminal).
        assertThatThrownBy(() -> svc.decide(e.getPatternId().toString(),
                new ApprovalIntent("approve", "bob", null), "trace-3"))
                .isInstanceOf(InvalidLifecycleStateException.class);
        assertThatThrownBy(() -> svc.deprecate(e.getPatternId().toString(),
                new DeprecateIntent("bob", null)))
                .isInstanceOf(InvalidLifecycleStateException.class);
    }

    // Criterion 9: deprecate an approved pattern -> deprecated + non-null timestamp.
    @Test
    void deprecateApprovedRemovesFromApprovedListing() {
        PatternEntity e = draft();
        e.setLifecycle("approved");
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));

        PatternView view = svc.deprecate(e.getPatternId().toString(),
                new DeprecateIntent("carol", "retired"));

        assertThat(view.lifecycle()).isEqualTo("deprecated");
        ArgumentCaptor<LifecycleTransitionEntity> cap =
                ArgumentCaptor.forClass(LifecycleTransitionEntity.class);
        verify(transitionRepository).save(cap.capture());
        assertThat(cap.getValue().getFromState()).isEqualTo("approved");
        assertThat(cap.getValue().getToState()).isEqualTo("deprecated");
        assertThat(cap.getValue().getTransitionedAt()).isNotNull();
    }

    // Approve on a non-draft pattern -> 409.
    @Test
    void approveNonDraftIsConflict() {
        PatternEntity e = draft();
        e.setLifecycle("approved");
        when(patternRepository.findById(e.getPatternId())).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> svc.decide(e.getPatternId().toString(),
                new ApprovalIntent("approve", "x", null), "t"))
                .isInstanceOf(InvalidLifecycleStateException.class);
        verify(eventPublisher, never()).publishApproved(any(), anyString());
    }
}
