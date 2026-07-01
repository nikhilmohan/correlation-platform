package com.acp.enrichment.pipeline;

import com.acp.enrichment.ruleset.AlarmTypeMap;
import com.acp.enrichment.ruleset.FieldMapping;
import com.acp.enrichment.ruleset.Ruleset;
import com.acp.eventmodel.ManagedObjectId;
import com.acp.eventmodel.generated.AlarmEvent;
import com.acp.eventmodel.generated.VendorRaw;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The single place per-source field mapping is applied (design "NormalizeStep"). Takes the raw
 * alarm payload (a generic map) plus the resolved {@link Ruleset#fieldMapping()} and produces a
 * canonical {@code AlarmEvent}: severity/eventType/probableCause translation, the REQUIRED
 * canonical {@code alarmType} via the source's {@code alarmTypeMap}, {@code managedObjectId}
 * construction, {@code vendorRaw} pass-through, and carry-over of {@code alarmId}/{@code state}/
 * timestamps.
 *
 * <p>If the raw alarm already carries a canonical {@code managedObjectId} (the Simulator emits
 * canonical alarms) it is used verbatim; otherwise the {@code managedObjectIdTemplate} builds it
 * from raw fields. All downstream stages operate only on this canonical form.
 */
@Component
public class NormalizeStep {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final MeterRegistry meters;

    public NormalizeStep(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * @param raw the raw alarm payload (field names per the source's conventions)
     * @param ruleset the resolved per-source ruleset
     * @return the canonical {@code AlarmEvent}
     * @throws NormalizeException if a required field cannot be produced, or an unmapped alarm-type
     *     under {@code onUnmapped=dlq}
     */
    public AlarmEvent normalize(Map<String, Object> raw, Ruleset ruleset) {
        FieldMapping fm = ruleset.fieldMapping();
        AlarmEvent out = new AlarmEvent();

        out.setAlarmId(reqStr(raw, "alarmId", ruleset, "alarmId"));
        out.setManagedObjectId(buildManagedObjectId(raw, fm, ruleset));
        out.setEventType(mapOrIdentity(rawEventTypeToken(raw), fm.eventTypeMap()));
        out.setProbableCause(probableCause(raw, fm));
        out.setAlarmType(resolveAlarmType(raw, fm.alarmTypeMap(), ruleset));
        out.setPerceivedSeverity(severity(raw, fm, ruleset));
        out.setRaisedAt(reqStr(raw, "raisedAt", ruleset, "raisedAt"));
        if (raw.get("clearedAt") != null) {
            out.setClearedAt(String.valueOf(raw.get("clearedAt")));
        }
        out.setState(state(raw, ruleset));
        out.setVendorRaw(vendorRaw(raw, fm));
        // trailIds set later by TrailTagStep; default to an empty (non-null) array meanwhile.
        out.setTrailIds(new ArrayList<>());
        return out;
    }

    private String buildManagedObjectId(Map<String, Object> raw, FieldMapping fm, Ruleset rs) {
        // Honour a pre-canonical managedObjectId if the source already provides one.
        Object existing = raw.get("managedObjectId");
        if (existing != null && ManagedObjectId.isValid(String.valueOf(existing))) {
            return String.valueOf(existing);
        }
        String template = fm.managedObjectIdTemplate();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value;
            if ("objectType".equals(key)) {
                Object ot = raw.get("objectType");
                value = ot != null ? String.valueOf(ot) : fm.defaultObjectType();
            } else {
                Object v = raw.get(key);
                if (v == null) {
                    throw new NormalizeException("normalize_invalid", "ruleset '" + rs.source()
                            + "' managedObjectIdTemplate references missing raw field '" + key + "'");
                }
                value = String.valueOf(v);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        String moId = sb.toString();
        if (!ManagedObjectId.isValid(moId)) {
            throw new NormalizeException("normalize_invalid",
                    "ruleset '" + rs.source() + "' produced invalid managedObjectId '" + moId + "'");
        }
        return moId;
    }

    private String resolveAlarmType(Map<String, Object> raw, AlarmTypeMap atm, Ruleset rs) {
        Object rawVal = raw.get(atm.rawField());
        String rawToken = rawVal == null ? null : String.valueOf(rawVal);
        String mapped = rawToken == null ? null : atm.values().get(rawToken);
        if (mapped != null) {
            return mapped;
        }
        // Unmapped raw alarm-type.
        if (atm.dlqOnUnmapped()) {
            throw new NormalizeException("alarmtype_unmapped", "ruleset '" + rs.source()
                    + "' has no alarmTypeMap entry for raw alarm-type '" + rawToken + "'");
        }
        meters.counter("alarmtype_fallback_total", "source", rs.source()).increment();
        return atm.fallback();
    }

    private String severity(Map<String, Object> raw, FieldMapping fm, Ruleset rs) {
        Object rawSev = firstNonNull(raw, "rawSeverity", "perceivedSeverity", "severity");
        if (rawSev == null) {
            throw new NormalizeException("normalize_invalid",
                    "ruleset '" + rs.source() + "' alarm missing a severity field");
        }
        String token = String.valueOf(rawSev);
        String mapped = fm.severityMap().get(token);
        return mapped != null ? mapped : token;
    }

    private String probableCause(Map<String, Object> raw, FieldMapping fm) {
        String rawToken = rawEventTypeToken(raw);
        String mapped = rawToken == null ? null : fm.probableCauseMap().get(rawToken);
        if (mapped != null) {
            return mapped;
        }
        Object existing = raw.get("probableCause");
        return existing != null ? String.valueOf(existing) : (rawToken != null ? rawToken : "unknown");
    }

    private AlarmEvent.State state(Map<String, Object> raw, Ruleset rs) {
        Object s = raw.get("state");
        if (s == null) {
            throw new NormalizeException("normalize_invalid",
                    "ruleset '" + rs.source() + "' alarm missing 'state'");
        }
        try {
            return AlarmEvent.State.fromValue(String.valueOf(s));
        } catch (IllegalArgumentException e) {
            throw new NormalizeException("normalize_invalid",
                    "ruleset '" + rs.source() + "' alarm has invalid state '" + s + "'");
        }
    }

    private VendorRaw vendorRaw(Map<String, Object> raw, FieldMapping fm) {
        VendorRaw vr = new VendorRaw();
        if (fm.passthroughAll()) {
            for (var e : raw.entrySet()) {
                vr.setAdditionalProperty(e.getKey(), e.getValue());
            }
        } else {
            for (String key : fm.vendorRawPassthrough()) {
                if (raw.containsKey(key)) {
                    vr.setAdditionalProperty(key, raw.get(key));
                }
            }
        }
        return vr;
    }

    private static String rawEventTypeToken(Map<String, Object> raw) {
        Object v = firstNonNull(raw, "rawEventType", "eventType", "rawAlarmType");
        return v == null ? null : String.valueOf(v);
    }

    private static String mapOrIdentity(String token, Map<String, String> map) {
        if (token == null) {
            return "unknown";
        }
        String mapped = map.get(token);
        return mapped != null ? mapped : token;
    }

    private static Object firstNonNull(Map<String, Object> raw, String... keys) {
        for (String k : keys) {
            Object v = raw.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String reqStr(Map<String, Object> raw, String key, Ruleset rs, String label) {
        Object v = raw.get(key);
        if (v == null) {
            throw new NormalizeException("normalize_invalid",
                    "ruleset '" + rs.source() + "' alarm missing required field '" + label + "'");
        }
        return String.valueOf(v);
    }
}
