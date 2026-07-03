package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SampleAlarmView;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SampleAlarmEntity;
import com.acp.patternmanager.store.entity.SequenceElementEntity;
import com.acp.patternmanager.store.repo.PatternRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Read-API serving of {@code sampleAlarms[]} on {@link PatternView} (spec-sample-alarms AC-SA-1..5).
 * Drives {@link PatternQueryService} + {@link PatternViewMapper} over a seeded Pattern Store fixture
 * (mocked {@link PatternRepository}) — no Kafka, no DB. Each AC maps 1:1 to a test.
 */
@ExtendWith(MockitoExtension.class)
class SampleAlarmReadApiTest {

    private static final Pattern MOID = Pattern.compile("^[A-Za-z][A-Za-z0-9]*:.+$");

    @Mock private PatternRepository patternRepository;

    private PatternQueryService svc;

    @BeforeEach
    void setUp() {
        svc = new PatternQueryService(patternRepository, new PatternViewMapper(new ObjectMapper()));
    }

    // AC-SA-1: GET /patterns/{id} returns a non-empty sampleAlarms[]; each entry has all 5 fields
    // non-null, raisedAt ISO-8601 UTC, managedObjectId <objectType>:<id>.
    @Test
    void singlePatternReturnsSampleWithSchema() {
        PatternEntity e = pattern(List.of("FiberFault", "LinkDown", "PortDown"), sample3());
        when(patternRepository.findById(e.getPatternId())).thenReturn(java.util.Optional.of(e));

        PatternView view = svc.get(e.getPatternId().toString());

        assertThat(view.sampleAlarms()).hasSize(3);
        for (SampleAlarmView sa : view.sampleAlarms()) {
            assertThat(sa.alarmId()).isNotBlank();
            assertThat(sa.alarmType()).isNotBlank();
            assertThat(sa.raisedAt()).isNotNull(); // OffsetDateTime -> serialized ISO-8601 UTC
            assertThat(sa.managedObjectId()).isNotBlank();
            assertThat(sa.perceivedSeverity()).isNotBlank();
            // raisedAt round-trips through ISO-8601 (OffsetDateTime.toString is ISO-8601).
            assertThatCode(() -> OffsetDateTime.parse(sa.raisedAt().toString())).doesNotThrowAnyException();
            assertThat(MOID.matcher(sa.managedObjectId()).matches()).isTrue();
        }
    }

    // AC-SA-2: every sample alarm's alarmType is a member of the pattern's sequence[].
    @Test
    void sampleAlarmTypesAreSequenceMembers() {
        List<String> sequence = List.of("FiberFault", "LinkDown", "PortDown");
        PatternEntity e = pattern(sequence, sample3());
        when(patternRepository.findById(e.getPatternId())).thenReturn(java.util.Optional.of(e));

        PatternView view = svc.get(e.getPatternId().toString());

        assertThat(view.sampleAlarms()).isNotEmpty();
        assertThat(view.sampleAlarms())
                .allSatisfy(sa -> assertThat(sequence).contains(sa.alarmType()));
    }

    // AC-SA-3: each managedObjectId conforms to <objectType>:<id> (exactly one colon after objectType
    // matching ^[A-Za-z][A-Za-z0-9]*$, and a non-empty id).
    @Test
    void managedObjectIdConformsToScheme() {
        PatternEntity e = pattern(List.of("FiberFault", "LinkDown", "PortDown"), sample3());
        when(patternRepository.findById(e.getPatternId())).thenReturn(java.util.Optional.of(e));

        PatternView view = svc.get(e.getPatternId().toString());

        for (SampleAlarmView sa : view.sampleAlarms()) {
            String moid = sa.managedObjectId();
            int firstColon = moid.indexOf(':');
            assertThat(firstColon).isPositive();
            String objectType = moid.substring(0, firstColon);
            String id = moid.substring(firstColon + 1);
            assertThat(objectType).matches("^[A-Za-z][A-Za-z0-9]*$");
            assertThat(id).isNotEmpty();
        }
    }

    // AC-SA-4: no sample captured -> sampleAlarms present and [] (not null, not absent).
    @Test
    void absentSampleServedAsEmptyList() {
        PatternEntity e = pattern(List.of("FiberFault"), List.of());
        when(patternRepository.findById(e.getPatternId())).thenReturn(java.util.Optional.of(e));

        PatternView view = svc.get(e.getPatternId().toString());

        assertThat(view.sampleAlarms()).isNotNull();
        assertThat(view.sampleAlarms()).isEmpty();
    }

    // AC-SA-5: GET /patterns (list) carries sampleAlarms on every item (one with, one without),
    // same content as GET /patterns/{id}; empty-sample item is [].
    @Test
    void listResponseCarriesSampleOnEveryItem() {
        PatternEntity withSample = pattern(List.of("FiberFault", "LinkDown", "PortDown"), sample3());
        PatternEntity withoutSample = pattern(List.of("LinkDown"), List.of());
        when(patternRepository.findByLifecycle(eq("draft"), any(Pageable.class)))
                .thenReturn(List.of(withSample, withoutSample));
        when(patternRepository.countByLifecycle("draft")).thenReturn(2L);

        PatternPage page = svc.list("draft", 50, 0, null);

        assertThat(page.items()).hasSize(2);
        // Both items carry the field (present, non-null).
        assertThat(page.items()).allSatisfy(v -> assertThat(v.sampleAlarms()).isNotNull());

        PatternView first = page.items().stream()
                .filter(v -> v.patternId().equals(withSample.getPatternId().toString()))
                .findFirst().orElseThrow();
        PatternView second = page.items().stream()
                .filter(v -> v.patternId().equals(withoutSample.getPatternId().toString()))
                .findFirst().orElseThrow();

        // Same content as single-get on the with-sample item.
        when(patternRepository.findById(withSample.getPatternId()))
                .thenReturn(java.util.Optional.of(withSample));
        PatternView single = svc.get(withSample.getPatternId().toString());
        assertThat(alarmIds(first)).isEqualTo(alarmIds(single));
        assertThat(first.sampleAlarms()).hasSize(3);
        // The empty-sample item is [].
        assertThat(second.sampleAlarms()).isEmpty();
    }

    private static List<String> alarmIds(PatternView v) {
        return v.sampleAlarms().stream().map(SampleAlarmView::alarmId).collect(Collectors.toList());
    }

    // --- fixture helpers ---

    private static List<SampleAlarmEntity> sample3() {
        return List.of(
                new SampleAlarmEntity(UUID.randomUUID(), null, "alm-1001", "FiberFault",
                        OffsetDateTime.parse("2026-06-20T14:03:11Z"),
                        "OpticalPort:lon-agg-1/xe-0/0/3", "critical", 0),
                new SampleAlarmEntity(UUID.randomUUID(), null, "alm-1002", "LinkDown",
                        OffsetDateTime.parse("2026-06-20T14:03:12Z"),
                        "Interface:lon-agg-1/ge-0/0/1", "major", 1),
                new SampleAlarmEntity(UUID.randomUUID(), null, "alm-1003", "PortDown",
                        OffsetDateTime.parse("2026-06-20T14:03:13Z"),
                        "Port:lon-core-2/et-1/1/2", "major", 2));
    }

    private static PatternEntity pattern(List<String> sequence, List<SampleAlarmEntity> samples) {
        PatternEntity e = new PatternEntity();
        e.setPatternId(UUID.randomUUID());
        e.setTrailId("trail:ospf-area0:7");
        e.setRootCauseAlarmType(sequence.get(0));
        e.setTimingJson("{\"timeframeMs\":3000}");
        e.setReconcileStatus("unexplained");
        e.setStructurallyValidated(true);
        e.setSessionWindowMs(5000);
        e.setSessionWindowType("gap-based");
        e.setInstanceCount(1);
        e.setLifecycle("draft");
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        for (int i = 0; i < sequence.size(); i++) {
            e.getSequenceElements().add(
                    new SequenceElementEntity(UUID.randomUUID(), e, i, sequence.get(i), false));
        }
        for (SampleAlarmEntity s : samples) {
            s.setPattern(e);
            e.getSampleAlarms().add(s);
        }
        return e;
    }
}
