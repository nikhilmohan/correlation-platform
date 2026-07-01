package com.acp.enrichment.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.enrichment.support.TestRulesets;
import com.acp.eventmodel.generated.AlarmEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Acceptance criteria 12, 13, 16 — per-source field mapping, default fallback, alarmType. */
class NormalizeStepTest {

    private final NormalizeStep step = new NormalizeStep(new SimpleMeterRegistry());

    private Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void differentSeverityCodesNormalizeToSameCanonicalSeverity() {
        // Criterion 12: nms-alpha CRIT and vendor-beta P1 both -> CRITICAL.
        AlarmEvent a = step.normalize(raw("alarmId", "a-1", "rawSeverity", "CRIT",
                "rawEventType", "LINK_DOWN", "ne", "edge1", "ifIndex", "12",
                "state", "raised", "raisedAt", "2026-06-11T10:00:00Z"), TestRulesets.nmsAlpha());
        AlarmEvent b = step.normalize(raw("alarmId", "b-1", "rawSeverity", "P1",
                "rawAlarmType", "port-fault", "chassis", "c9", "slot", "3", "port", "7",
                "state", "raised", "raisedAt", "2026-06-11T10:00:00Z"), TestRulesets.vendorBeta());

        assertThat(a.getPerceivedSeverity()).isEqualTo("CRITICAL");
        assertThat(b.getPerceivedSeverity()).isEqualTo("CRITICAL");
        assertThat(a.getManagedObjectId()).isEqualTo("Interface:edge1-12");
        assertThat(b.getManagedObjectId()).isEqualTo("Port:c9-3-7");
        // No raw source-specific severity codes in canonical fields.
        assertThat(a.getPerceivedSeverity()).isNotEqualTo("CRIT");
    }

    @Test
    void emittedAlarmTypeIsVocabTokenFromSourceMap() {
        // Criterion 16: nms-alpha LINK_DOWN -> LinkDown; vendor-beta port-fault -> PortDown.
        AlarmEvent a = step.normalize(raw("alarmId", "a-1", "rawSeverity", "CRIT",
                "rawEventType", "LINK_DOWN", "ne", "edge1", "ifIndex", "12",
                "state", "raised", "raisedAt", "2026-06-11T10:00:00Z"), TestRulesets.nmsAlpha());
        AlarmEvent b = step.normalize(raw("alarmId", "b-1", "rawSeverity", "P1",
                "rawAlarmType", "port-fault", "chassis", "c9", "slot", "3", "port", "7",
                "state", "raised", "raisedAt", "2026-06-11T10:00:00Z"), TestRulesets.vendorBeta());

        assertThat(a.getAlarmType()).isEqualTo("LinkDown");
        assertThat(b.getAlarmType()).isEqualTo("PortDown");
    }

    @Test
    void unmappedAlarmTypeUsesFallbackTokenInDefaultMode() {
        // Criterion 16 (unmapped path): nms-alpha onUnmapped=default -> fallback ReachabilityLoss.
        AlarmEvent a = step.normalize(raw("alarmId", "a-1", "rawSeverity", "CRIT",
                "rawEventType", "SOMETHING_NEW", "ne", "edge1", "ifIndex", "12",
                "state", "raised", "raisedAt", "2026-06-11T10:00:00Z"), TestRulesets.nmsAlpha());
        assertThat(a.getAlarmType()).isEqualTo("ReachabilityLoss");
    }

    @Test
    void unmatchedSourceDefaultRulesetProducesCanonicalAlarm() {
        // Criterion 13: the default ruleset normalizes an alarm whose source matched nothing.
        AlarmEvent a = step.normalize(raw("alarmId", "d-1", "rawSeverity", "1",
                "rawAlarmType", "2", "objectType", "Node", "rawObjectId", "core-7",
                "state", "raised", "raisedAt", "2026-06-11T10:00:00Z"),
                TestRulesets.defaultRuleset());
        assertThat(a.getPerceivedSeverity()).isEqualTo("CRITICAL");
        assertThat(a.getAlarmType()).isEqualTo("LinkDown");
        assertThat(a.getManagedObjectId()).isEqualTo("Node:core-7");
    }

    @Test
    void simulatorAlarmPassesThroughWithCanonicalAlarmTypePreserved() {
        // FIX #1: a source=simulator alarm carries ALREADY-canonical fields (canonical
        // managedObjectId, canonical alarmType token in the `alarmType` field, canonical eventType,
        // lowercase perceivedSeverity). The identity ruleset must preserve them verbatim — in
        // particular alarmType=InterfaceDown must NOT collapse to the fallback (the pre-fix bug where
        // no simulator ruleset existed and everything fell back to ReachabilityLoss).
        AlarmEvent a = step.normalize(raw(
                "alarmId", "sim-1",
                "managedObjectId", "Node:N5",
                "eventType", "communicationsAlarm",
                "probableCause", "interfaceDown",
                "alarmType", "InterfaceDown",
                "perceivedSeverity", "major",
                "raisedAt", "2026-06-14T08:15:00Z",
                "state", "raised"), TestRulesets.simulator());

        assertThat(a.getAlarmType()).as("canonical alarmType preserved, not fallback")
                .isEqualTo("InterfaceDown");
        assertThat(a.getManagedObjectId()).isEqualTo("Node:N5");
        assertThat(a.getEventType()).isEqualTo("communicationsAlarm");
        assertThat(a.getProbableCause()).isEqualTo("interfaceDown");
        assertThat(a.getPerceivedSeverity()).isEqualTo("major");
        assertThat(a.getState()).isEqualTo(AlarmEvent.State.RAISED);
    }

    @Test
    void simulatorVpnServiceAlarmPreservesTypeAndManagedObjectId() {
        // FIX #1: another canonical simulator shape (VPNService object + a non-MVP 30-token type).
        AlarmEvent a = step.normalize(raw(
                "alarmId", "sim-2",
                "managedObjectId", "VPNService:vpn-42",
                "eventType", "communicationsAlarm",
                "probableCause", "reachabilityLoss",
                "alarmType", "VPNReachabilityLoss",
                "perceivedSeverity", "critical",
                "raisedAt", "2026-06-14T09:00:00Z",
                "state", "raised"), TestRulesets.simulator());

        assertThat(a.getAlarmType()).isEqualTo("VPNReachabilityLoss");
        assertThat(a.getManagedObjectId()).isEqualTo("VPNService:vpn-42");
    }

    @Test
    void invalidManagedObjectIdRoutesToNormalizeFailure() {
        assertThatThrownBy(() -> step.normalize(raw("alarmId", "a-1", "rawSeverity", "CRIT",
                "rawEventType", "LINK_DOWN", "state", "raised", "raisedAt",
                "2026-06-11T10:00:00Z"), TestRulesets.nmsAlpha()))
                .isInstanceOf(NormalizeException.class);
    }
}
