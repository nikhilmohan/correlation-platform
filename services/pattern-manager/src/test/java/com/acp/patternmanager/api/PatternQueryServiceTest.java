package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.error.PatternNotFoundException;
import com.acp.patternmanager.store.entity.PatternEntity;
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
import org.springframework.data.domain.Pageable;

/** Read API: PatternPage envelope (12b), lifecycle filter (5, 13), trailId (12c), 404, invalid enum. */
@ExtendWith(MockitoExtension.class)
class PatternQueryServiceTest {

    @Mock private PatternRepository patternRepository;

    private PatternQueryService svc;

    @BeforeEach
    void setUp() {
        svc = new PatternQueryService(patternRepository, new PatternViewMapper(new ObjectMapper()));
    }

    private PatternEntity pattern(String lifecycle, String trailId) {
        PatternEntity e = new PatternEntity();
        e.setPatternId(UUID.randomUUID());
        e.setTrailId(trailId);
        e.setRootCauseAlarmType("LOS");
        e.setTimingJson("{\"timeframeMs\":3000}");
        e.setReconcileStatus("unexplained");
        e.setStructurallyValidated(true);
        e.setSessionWindowMs(5000);
        e.setSessionWindowType("gap-based");
        e.setInstanceCount(1);
        e.setLifecycle(lifecycle);
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        return e;
    }

    // Criteria 5 + 12b + 12c: draft filter returns PatternPage envelope items carrying trailId.
    @Test
    void listReturnsPatternPageEnvelopeWithTrailIdForLifecycleFilter() {
        PatternEntity draft = pattern("draft", "trail-42");
        when(patternRepository.findByLifecycle(eq("draft"), any(Pageable.class)))
                .thenReturn(List.of(draft));
        when(patternRepository.countByLifecycle("draft")).thenReturn(1L);

        PatternPage page = svc.list("draft", 50, 0, "-createdAt");

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.limit()).isEqualTo(50);
        assertThat(page.offset()).isEqualTo(0);
        PatternView view = page.items().get(0);
        assertThat(view.lifecycle()).isEqualTo("draft");
        assertThat(view.trailId()).isEqualTo("trail-42"); // P3-G1 CE source
        assertThat(view.sessionWindow().windowMs()).isEqualTo(5000L);
        assertThat(view.sessionWindow().type()).isEqualTo("gap-based");
    }

    // Criterion 13: approved filter returns only approved.
    @Test
    void approvedFilterReturnsOnlyApproved() {
        when(patternRepository.findByLifecycle(eq("approved"), any(Pageable.class)))
                .thenReturn(List.of(pattern("approved", "t1"), pattern("approved", "t2")));
        when(patternRepository.countByLifecycle("approved")).thenReturn(2L);

        PatternPage page = svc.list("approved", 50, 0, null);
        assertThat(page.items()).allMatch(v -> v.lifecycle().equals("approved"));
    }

    @Test
    void getByIdUnknownIs404() {
        UUID id = UUID.randomUUID();
        when(patternRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.get(id.toString())).isInstanceOf(PatternNotFoundException.class);
        // a non-UUID id is also 404, not a 500
        assertThatThrownBy(() -> svc.get("not-a-uuid")).isInstanceOf(PatternNotFoundException.class);
    }

    @Test
    void invalidLifecycleOrSortEnumIsBadRequest() {
        assertThatThrownBy(() -> svc.list("bogus", 50, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.list(null, 50, 0, "bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
