package com.acp.patternmanager.api;

import com.acp.patternmanager.api.dto.ApprovalIntent;
import com.acp.patternmanager.api.dto.DeprecateIntent;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.error.InvalidLifecycleStateException;
import com.acp.patternmanager.api.error.PatternNotFoundException;
import com.acp.patternmanager.event.PatternEventPublisher;
import com.acp.patternmanager.store.entity.LifecycleTransitionEntity;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.LifecycleTransitionRepository;
import com.acp.patternmanager.store.repo.PatternRepository;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the lifecycle transitions (design task 11/14). Approve: {@code draft -> approved} + audit +
 * emit {@code PatternApprovedEvent}. Reject (Q1): {@code draft -> rejected} (terminal) + audit + NO
 * event. Deprecate: {@code draft|approved -> deprecated} + audit. Any action on the wrong state is
 * a 409; {@code rejected}/{@code deprecated} are terminal.
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    private final PatternRepository patternRepository;
    private final LifecycleTransitionRepository transitionRepository;
    private final PatternEventPublisher eventPublisher;
    private final PatternViewMapper mapper;

    public LifecycleService(PatternRepository patternRepository,
            LifecycleTransitionRepository transitionRepository,
            PatternEventPublisher eventPublisher, PatternViewMapper mapper) {
        this.patternRepository = patternRepository;
        this.transitionRepository = transitionRepository;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
    }

    /**
     * Apply an approve/reject decision to a {@code draft} pattern.
     *
     * @param patternId the pattern id
     * @param intent the approval intent (decision + reviewer + notes)
     * @param traceId trace id for the emitted approved event (approve only)
     * @return the updated pattern view
     */
    @Transactional
    public PatternView decide(String patternId, ApprovalIntent intent, String traceId) {
        PatternEntity entity = load(patternId);
        requireState(entity, patternId, Set.of("draft"));

        OffsetDateTime now = OffsetDateTime.now();
        if ("approve".equals(intent.decision())) {
            transition(entity, "approved", intent.reviewer(), intent.notes(), now);
            patternRepository.save(entity);
            eventPublisher.publishApproved(entity, traceId);
            log.info("pattern {} approved by {}", patternId, intent.reviewer());
        } else {
            // reject (Q1): terminal, no event.
            transition(entity, "rejected", intent.reviewer(), intent.notes(), now);
            patternRepository.save(entity);
            log.info("pattern {} rejected by {} (terminal, no event)", patternId, intent.reviewer());
        }
        return mapper.toView(entity);
    }

    /**
     * Deprecate a {@code draft} or {@code approved} pattern.
     *
     * @param patternId the pattern id
     * @param intent the deprecate intent
     * @return the updated pattern view
     */
    @Transactional
    public PatternView deprecate(String patternId, DeprecateIntent intent) {
        PatternEntity entity = load(patternId);
        requireState(entity, patternId, Set.of("draft", "approved"));
        transition(entity, "deprecated", intent.reviewer(), intent.notes(), OffsetDateTime.now());
        patternRepository.save(entity);
        log.info("pattern {} deprecated by {}", patternId, intent.reviewer());
        return mapper.toView(entity);
    }

    private void transition(PatternEntity entity, String toState, String reviewer, String notes,
            OffsetDateTime at) {
        String from = entity.getLifecycle();
        entity.setLifecycle(toState);
        entity.setUpdatedAt(at);
        transitionRepository.save(new LifecycleTransitionEntity(
                UUID.randomUUID(), entity.getPatternId(), from, toState, reviewer, notes, at));
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

    private void requireState(PatternEntity entity, String patternId, Set<String> allowed) {
        if (!allowed.contains(entity.getLifecycle())) {
            throw new InvalidLifecycleStateException(patternId,
                    "pattern " + patternId + " is in state '" + entity.getLifecycle()
                            + "', expected one of " + allowed);
        }
    }
}
