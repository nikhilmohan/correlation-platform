package com.acp.enrichment.support;

import com.acp.enrichment.ruleset.AlarmTypeMap;
import com.acp.enrichment.ruleset.ChatterEntry;
import com.acp.enrichment.ruleset.FieldMapping;
import com.acp.enrichment.ruleset.FilterParams;
import com.acp.enrichment.ruleset.Ruleset;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Small hand-authored rulesets used across the unit tests (mirrors the design's worked examples). */
public final class TestRulesets {

    private TestRulesets() {
    }

    public static AlarmTypeMap nmsAlphaAlarmTypeMap() {
        return new AlarmTypeMap("rawEventType",
                Map.of("LINK_DOWN", "LinkDown", "LOS", "LOS", "PORT_DOWN", "PortDown",
                        "IF_DOWN", "InterfaceDown"),
                "ReachabilityLoss", "default");
    }

    /** nms-alpha: CRIT->CRITICAL, short 5s hold, flapN 3, one chatter entry. */
    public static Ruleset nmsAlpha() {
        return new Ruleset("nms-alpha", false,
                new FieldMapping("Interface", "Interface:{ne}-{ifIndex}",
                        Map.of("CRIT", "CRITICAL", "MAJ", "MAJOR", "MIN", "MINOR", "WARN", "WARNING",
                                "CLR", "CLEARED"),
                        Map.of("LINK_DOWN", "communicationsAlarm", "LOS", "communicationsAlarm"),
                        Map.of("LINK_DOWN", "linkDown", "LOS", "lossOfSignal"),
                        nmsAlphaAlarmTypeMap(),
                        List.of("ne", "ifIndex", "rawSeverity", "vendorCode")),
                new FilterParams(Duration.ofSeconds(20), Duration.ofSeconds(5), 3,
                        Duration.ofSeconds(45),
                        List.of(new ChatterEntry("Interface:edge1-12", "communicationsAlarm",
                                "LinkDown", null))));
    }

    /** vendor-beta: P1->CRITICAL, long 120s hold, flapN 8, empty chatter. */
    public static Ruleset vendorBeta() {
        return new Ruleset("vendor-beta", false,
                new FieldMapping("Port", "Port:{chassis}-{slot}-{port}",
                        Map.of("P1", "CRITICAL", "P2", "MAJOR", "P3", "MINOR", "P4", "WARNING",
                                "OK", "CLEARED"),
                        Map.of("port-fault", "equipmentAlarm", "card-fault", "equipmentAlarm"),
                        Map.of("port-fault", "equipmentMalfunction"),
                        new AlarmTypeMap("rawAlarmType",
                                Map.of("port-fault", "PortDown", "card-fault", "FiberFault",
                                        "los", "LOS"),
                                "ReachabilityLoss", "default"),
                        List.of("chassis", "slot", "port", "code")),
                new FilterParams(Duration.ofSeconds(60), Duration.ofSeconds(120), 8,
                        Duration.ofSeconds(180), List.of()));
    }

    /**
     * flap-source: a source tuned for the full-pipeline flap test — a short self-clear hold (1s) so
     * raises are released quickly into the flap stage, flapN=3 within a wide 300s window, and a tiny
     * dedup window (1s) so distinct-state oscillation alarms are not collapsed. Field mapping mirrors
     * nms-alpha (Interface objects, LINK_DOWN -> LinkDown).
     */
    public static Ruleset flapSource() {
        return new Ruleset("flap-source", false,
                new FieldMapping("Interface", "Interface:{ne}-{ifIndex}",
                        Map.of("CRIT", "CRITICAL", "MAJ", "MAJOR", "MIN", "MINOR", "WARN", "WARNING",
                                "CLR", "CLEARED"),
                        Map.of("LINK_DOWN", "communicationsAlarm", "LOS", "communicationsAlarm"),
                        Map.of("LINK_DOWN", "linkDown", "LOS", "lossOfSignal"),
                        nmsAlphaAlarmTypeMap(),
                        List.of("ne", "ifIndex", "rawSeverity")),
                new FilterParams(Duration.ofSeconds(1), Duration.ofSeconds(1), 3,
                        Duration.ofSeconds(300), List.of()));
    }

    /** default: numeric severity, 30s/15s/flapN 5. */
    public static Ruleset defaultRuleset() {
        return new Ruleset("default", true,
                new FieldMapping("Node", "{objectType}:{rawObjectId}",
                        Map.of("1", "CRITICAL", "2", "MAJOR", "3", "MINOR", "4", "WARNING",
                                "5", "CLEARED"),
                        Map.of(), Map.of(),
                        new AlarmTypeMap("rawAlarmType",
                                Map.of("1", "ReachabilityLoss", "2", "LinkDown", "3",
                                        "InterfaceDown"),
                                "ReachabilityLoss", "default"),
                        List.of("*")),
                new FilterParams(Duration.ofSeconds(30), Duration.ofSeconds(15), 5,
                        Duration.ofSeconds(60), List.of()));
    }
}
