package com.acp.enrichment.ruleset;

import com.acp.enrichment.chatter.ChatterOverlayStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads the mounted per-source rulesets YAML into immutable {@link Ruleset}s, validates them,
 * merges the durable chatter overlay, and publishes an atomically-swappable snapshot into the
 * {@link RulesetRegistry} (design "Config model", "Loading and hot-reload", "Chatter edit
 * persistence and hot-apply").
 *
 * <p>Bad base config (missing file, unparseable YAML, no {@code default}, malformed
 * mapping/params, non-vocab {@code alarmType} value) fails the initial load so startup readiness
 * stays down. A corrupt overlay degrades to base-YAML-only (not fatal).
 *
 * <p>This class is wholly internal to Enrichment — there is no {@code knowledge.updated} consumer
 * and no {@code KnowledgeClient}.
 */
public class RulesetConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(RulesetConfigLoader.class);

    private final Path rulesetsFile;
    private final ChatterOverlayStore overlayStore;
    private final RulesetRegistry registry;
    private final AlarmTypeVocabulary vocabulary;
    private final MeterRegistry meters;

    public RulesetConfigLoader(Path rulesetsFile, ChatterOverlayStore overlayStore,
            RulesetRegistry registry, AlarmTypeVocabulary vocabulary, MeterRegistry meters) {
        this.rulesetsFile = rulesetsFile;
        this.overlayStore = overlayStore;
        this.registry = registry;
        this.vocabulary = vocabulary;
        this.meters = meters;
    }

    /**
     * Parse + validate the base YAML, load the overlay, build the effective snapshot, and publish
     * it. Used at startup; a failure here means readiness stays down.
     *
     * @throws IllegalStateException on missing/unparseable/invalid base config
     */
    public synchronized void loadInitial() {
        List<Ruleset> base = parseBase();
        try {
            overlayStore.load();
        } catch (IOException | RuntimeException e) {
            // Corrupt overlay is non-fatal: start from base YAML only (design error-handling).
            meters.counter("chatter_overlay_load_failures_total").increment();
            log.error("chatter overlay unreadable, starting from base YAML only: {}",
                    e.getMessage());
        }
        registry.swap(buildSnapshot(base));
        log.info("loaded {} rulesets (default present={})", base.size(), true);
    }

    /**
     * Rebuild the effective snapshot from base YAML + overlay and atomically swap it in. Used by
     * the chatter-edit hot-apply path and the file watcher. On a base-config validation failure the
     * last-good snapshot is kept.
     */
    public synchronized void rebuildSnapshot() {
        try {
            List<Ruleset> base = parseBase();
            registry.swap(buildSnapshot(base));
        } catch (RuntimeException e) {
            meters.counter("ruleset_reload_failures_total").increment();
            log.error("ruleset reload failed, keeping last-good snapshot: {}", e.getMessage());
            throw e;
        }
    }

    private RulesetRegistry.Snapshot buildSnapshot(List<Ruleset> base) {
        List<Ruleset> effective = new ArrayList<>();
        for (Ruleset r : base) {
            effective.add(applyOverlay(r));
        }
        return RulesetRegistry.snapshotOf(effective);
    }

    /** Merge a source's effective chatterList = base entries + overlay adds - overlay removes. */
    private Ruleset applyOverlay(Ruleset r) {
        List<ChatterEntry> effective = new ArrayList<>(r.filterParams().chatterList());
        for (ChatterEntry rm : overlayStore.removes(r.source())) {
            effective.removeIf(e -> e.matchesKey(rm));
        }
        for (ChatterEntry add : overlayStore.adds(r.source())) {
            if (effective.stream().noneMatch(e -> e.matchesKey(add))) {
                effective.add(add);
            }
        }
        return r.withFilterParams(r.filterParams().withChatterList(effective));
    }

    @SuppressWarnings("unchecked")
    private List<Ruleset> parseBase() {
        if (rulesetsFile == null || !Files.exists(rulesetsFile)) {
            throw new IllegalStateException("rulesets file not found: " + rulesetsFile);
        }
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(rulesetsFile)) {
            root = new Yaml().load(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("rulesets file unparseable: " + e.getMessage(), e);
        }
        if (root == null || !root.containsKey("rulesets")) {
            throw new IllegalStateException("rulesets file missing 'rulesets' list");
        }
        List<Map<String, Object>> rulesetNodes = (List<Map<String, Object>>) root.get("rulesets");
        List<Ruleset> out = new ArrayList<>();
        boolean sawDefault = false;
        for (Map<String, Object> node : rulesetNodes) {
            Ruleset r = parseRuleset(node);
            sawDefault |= r.isDefault();
            out.add(r);
        }
        if (!sawDefault) {
            throw new IllegalStateException("rulesets file must include a 'default' ruleset");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Ruleset parseRuleset(Map<String, Object> node) {
        String source = str(node, "source");
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("ruleset entry missing 'source'");
        }
        boolean isDefault = Ruleset.DEFAULT_SOURCE.equals(source);

        Map<String, Object> fm = (Map<String, Object>) require(node, "fieldMapping", source);
        FieldMapping fieldMapping = parseFieldMapping(fm, source);

        Map<String, Object> fp = (Map<String, Object>) require(node, "filterParams", source);
        FilterParams filterParams = parseFilterParams(fp, source);

        return new Ruleset(source, isDefault, fieldMapping, filterParams);
    }

    @SuppressWarnings("unchecked")
    private FieldMapping parseFieldMapping(Map<String, Object> fm, String source) {
        String template = str(fm, "managedObjectIdTemplate");
        if (template == null || template.isBlank()) {
            throw new IllegalStateException(
                    "ruleset '" + source + "' fieldMapping missing managedObjectIdTemplate");
        }
        AlarmTypeMap atm = parseAlarmTypeMap(
                (Map<String, Object>) require(fm, "alarmTypeMap", source), source);
        return new FieldMapping(
                str(fm, "defaultObjectType"),
                template,
                strMap(fm.get("severityMap")),
                strMap(fm.get("eventTypeMap")),
                strMap(fm.get("probableCauseMap")),
                atm,
                strList(fm.get("vendorRawPassthrough")));
    }

    private AlarmTypeMap parseAlarmTypeMap(Map<String, Object> atm, String source) {
        String rawField = str(atm, "rawField");
        if (rawField == null || rawField.isBlank()) {
            throw new IllegalStateException(
                    "ruleset '" + source + "' alarmTypeMap missing rawField");
        }
        Map<String, String> values = strMap(atm.get("values"));
        String fallback = str(atm, "fallback");
        String onUnmapped = str(atm, "onUnmapped");
        // Validate every mapped value AND the fallback are valid vocabulary tokens.
        for (var e : values.entrySet()) {
            if (!vocabulary.contains(e.getValue())) {
                throw new IllegalStateException("ruleset '" + source + "' alarmTypeMap value '"
                        + e.getValue() + "' is not a valid alarmTypeVocabulary token");
            }
        }
        if (fallback != null && !vocabulary.contains(fallback)) {
            throw new IllegalStateException("ruleset '" + source + "' alarmTypeMap fallback '"
                    + fallback + "' is not a valid alarmTypeVocabulary token");
        }
        if (!AlarmTypeMap.ON_UNMAPPED_DLQ.equalsIgnoreCase(onUnmapped)
                && (fallback == null || fallback.isBlank())) {
            throw new IllegalStateException("ruleset '" + source
                    + "' alarmTypeMap requires a 'fallback' token unless onUnmapped=dlq");
        }
        return new AlarmTypeMap(rawField, values, fallback, onUnmapped);
    }

    @SuppressWarnings("unchecked")
    private FilterParams parseFilterParams(Map<String, Object> fp, String source) {
        Duration dedup = duration(fp.get("dedupWindow"), source, "dedupWindow");
        Duration hold = duration(fp.get("selfClearHoldTime"), source, "selfClearHoldTime");
        int flapN = intVal(fp.get("flapN"), source, "flapN");
        Duration flapWindow = duration(fp.get("flapWindow"), source, "flapWindow");
        List<ChatterEntry> chatter = new ArrayList<>();
        Object cl = fp.get("chatterList");
        if (cl instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> e = (Map<String, Object>) o;
                chatter.add(new ChatterEntry(str(e, "managedObjectId"), str(e, "eventType"),
                        str(e, "alarmType"), str(e, "promotedFrom")));
            }
        }
        return new FilterParams(dedup, hold, flapN, flapWindow, chatter);
    }

    // ---- small parsing helpers -------------------------------------------------------------

    private static Object require(Map<String, Object> m, String key, String source) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalStateException("ruleset '" + source + "' missing '" + key + "'");
        }
        return v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object v : list) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    private static Duration duration(Object v, String source, String field) {
        if (v == null) {
            throw new IllegalStateException(
                    "ruleset '" + source + "' filterParams missing '" + field + "'");
        }
        String s = String.valueOf(v).trim();
        try {
            // Accept compact forms: "30s", "5m", "2h", "1500ms".
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            // ISO-8601 (e.g. PT30S) or plain seconds.
            if (s.startsWith("P") || s.startsWith("p")) {
                return Duration.parse(s.toUpperCase());
            }
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalStateException("ruleset '" + source + "' filterParams '" + field
                    + "' is not a valid duration: " + s, e);
        }
    }

    private static int intVal(Object v, String source, String field) {
        if (v == null) {
            throw new IllegalStateException(
                    "ruleset '" + source + "' filterParams missing '" + field + "'");
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("ruleset '" + source + "' filterParams '" + field
                    + "' is not an integer: " + v, e);
        }
    }
}
