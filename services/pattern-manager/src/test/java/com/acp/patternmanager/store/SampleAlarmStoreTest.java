package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.config.SampleAlarmProperties;
import com.acp.patternmanager.enrichment.SampleAlarm;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.entity.SampleAlarmEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Ingest-side sample-alarm persistence behaviour on {@link PatternStoreService#setSampleAlarms}
 * (spec-sample-alarms AC-SA-5a parse->persist mapping, AC-SA-6 bounded cap K, backward-compat).
 * Pure unit test — mutates the entity collection directly; the true DB round-trip is
 * {@code SampleAlarmPersistenceIT} (real Postgres).
 */
class SampleAlarmStoreTest {

    private PatternStoreService store(int capK) {
        return new PatternStoreService(null, null, null, new ObjectMapper(),
                new SampleAlarmProperties(capK));
    }

    private static SampleAlarm sa(String id, String type, String moid) {
        return new SampleAlarm(id, type, OffsetDateTime.parse("2026-06-20T14:03:11Z"), moid, "major");
    }

    // AC-SA-5a: each parsed sample is written as a child row preserving the miner's order (position).
    @Test
    void persistsSampleRowsPreservingOrder() {
        PatternEntity p = new PatternEntity();
        List<SampleAlarm> samples = List.of(
                sa("alm-1", "FiberFault", "OpticalPort:o1"),
                sa("alm-2", "LinkDown", "Interface:i1"),
                sa("alm-3", "PortDown", "Port:p1"));

        store(10).setSampleAlarms(p, samples);

        assertThat(p.getSampleAlarms()).hasSize(3);
        assertThat(p.getSampleAlarms()).extracting(SampleAlarmEntity::getAlarmId)
                .containsExactly("alm-1", "alm-2", "alm-3");
        assertThat(p.getSampleAlarms()).extracting(SampleAlarmEntity::getPosition)
                .containsExactly(0, 1, 2);
    }

    // AC-SA-6: K=3 and 5 sample entries -> at most 3 rows retained (first K, deterministically).
    @Test
    void capsSampleToK() {
        PatternEntity p = new PatternEntity();
        List<SampleAlarm> five = IntStream.range(0, 5)
                .mapToObj(i -> sa("alm-" + i, "LinkDown", "Interface:i" + i))
                .toList();

        store(3).setSampleAlarms(p, five);

        assertThat(p.getSampleAlarms()).hasSize(3);
        assertThat(p.getSampleAlarms()).extracting(SampleAlarmEntity::getAlarmId)
                .containsExactly("alm-0", "alm-1", "alm-2");
    }

    // AC-SA-5b backward-compat: empty/null sample -> zero rows (pattern still persisted normally).
    @Test
    void emptyOrNullSampleWritesZeroRows() {
        PatternEntity p1 = new PatternEntity();
        store(10).setSampleAlarms(p1, List.of());
        assertThat(p1.getSampleAlarms()).isEmpty();

        PatternEntity p2 = new PatternEntity();
        store(10).setSampleAlarms(p2, null);
        assertThat(p2.getSampleAlarms()).isEmpty();
    }
}
