package com.acp.enrichment.ruleset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the effective {@link Ruleset}s indexed by source identifier plus the mandatory
 * {@code default} ruleset. Backed by an immutable snapshot held in an {@link AtomicReference} and
 * <b>atomically swapped</b> on reload / chatter edit, so in-flight alarms always see either the old
 * or the new whole snapshot, never a partial one (design "RulesetRegistry").
 */
public final class RulesetRegistry {

    /** An immutable point-in-time set of rulesets. */
    public record Snapshot(Map<String, Ruleset> bySource, Ruleset defaultRuleset) {
        public Snapshot {
            bySource = Map.copyOf(bySource);
        }
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /**
     * Build a snapshot from the given rulesets (one MUST be the {@code default}).
     *
     * @param rulesets the effective rulesets
     * @return an immutable snapshot
     * @throws IllegalArgumentException if no {@code default} ruleset is present
     */
    public static Snapshot snapshotOf(List<Ruleset> rulesets) {
        Map<String, Ruleset> bySource = new HashMap<>();
        Ruleset def = null;
        for (Ruleset r : rulesets) {
            bySource.put(r.source(), r);
            if (r.isDefault() || Ruleset.DEFAULT_SOURCE.equals(r.source())) {
                def = r;
            }
        }
        if (def == null) {
            throw new IllegalArgumentException(
                    "ruleset configuration must include exactly one 'default' ruleset");
        }
        return new Snapshot(bySource, def);
    }

    /** Atomically swap in a new snapshot (last-writer-wins). */
    public void swap(Snapshot snapshot) {
        current.set(snapshot);
    }

    /** @return {@code true} iff a valid snapshot (with a {@code default}) has been loaded. */
    public boolean isLoaded() {
        return current.get() != null;
    }

    /** @return the current immutable snapshot, or {@code null} if none loaded yet. */
    public Snapshot snapshot() {
        return current.get();
    }

    /**
     * @param source the envelope source selector
     * @return the ruleset for {@code source}, or the {@code default} ruleset when no source-specific
     *     ruleset matches (criterion 13). Never {@code null} once loaded.
     */
    public Ruleset forSource(String source) {
        Snapshot snap = requireLoaded();
        Ruleset r = source == null ? null : snap.bySource().get(source);
        return r != null ? r : snap.defaultRuleset();
    }

    /** @return the mandatory built-in default ruleset. */
    public Ruleset getDefault() {
        return requireLoaded().defaultRuleset();
    }

    /** @param source the source selector @return {@code true} iff a source-specific ruleset exists. */
    public boolean hasSource(String source) {
        return source != null && requireLoaded().bySource().containsKey(source);
    }

    private Snapshot requireLoaded() {
        Snapshot snap = current.get();
        if (snap == null) {
            throw new IllegalStateException("RulesetRegistry has no loaded snapshot");
        }
        return snap;
    }
}
