package com.acp.enrichment.chatter;

import com.acp.enrichment.ruleset.ChatterEntry;
import com.acp.enrichment.ruleset.Ruleset;
import com.acp.enrichment.ruleset.RulesetConfigLoader;
import com.acp.enrichment.ruleset.RulesetRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a chatter edit (design "ChatterService + ChatterOverlayStore"): durably record the
 * add/remove in the {@link ChatterOverlayStore}, then ask {@link RulesetConfigLoader} to rebuild the
 * effective registry snapshot (base YAML + overlay) and atomically swap it into the
 * {@link RulesetRegistry} — the same swap path the YAML watcher uses. Because the live
 * {@code ChatterStep} reads the chatter list from the resolved {@code Ruleset} in the swapped
 * registry, a promoted/removed entry takes effect on the very next alarm with no restart (criteria
 * 19, 20). All edits are serialized through this single writer.
 */
public class ChatterService {

    private static final Logger log = LoggerFactory.getLogger(ChatterService.class);

    /** Raised on a validation rejection; carries an {@code Error.code} for the API mapping. */
    public static class ChatterValidationException extends RuntimeException {
        private final String code;

        public ChatterValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Raised when the overlay write or registry rebuild fails (all-or-nothing → 500). */
    public static class ChatterEditException extends RuntimeException {
        public ChatterEditException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RulesetRegistry registry;
    private final ChatterOverlayStore overlayStore;
    private final RulesetConfigLoader loader;
    private final MeterRegistry meters;
    private final Object writeLock = new Object();

    public ChatterService(RulesetRegistry registry, ChatterOverlayStore overlayStore,
            RulesetConfigLoader loader, MeterRegistry meters) {
        this.registry = registry;
        this.overlayStore = overlayStore;
        this.loader = loader;
        this.meters = meters;
    }

    /**
     * @param source the source (must be a configured ruleset)
     * @return the source's effective chatter list (base YAML + overlay)
     * @throws ChatterValidationException {@code unknown_source} if not a configured ruleset
     */
    public List<ChatterEntry> list(String source) {
        requireKnownSource(source);
        return registry.forSource(source).filterParams().chatterList();
    }

    /**
     * Add (promote) a chatter entry. Validates the source + match key, rejects an already-present
     * entry, then persists + hot-applies.
     *
     * @return the stored entry
     */
    public ChatterEntry add(String source, ChatterEntry entry) {
        requireKnownSource(source);
        requireValidKey(entry);
        synchronized (writeLock) {
            boolean present = registry.forSource(source).filterParams().chatterList().stream()
                    .anyMatch(e -> e.matchesKey(entry));
            if (present) {
                meters.counter("chatter_api_rejected_total", "reason", "duplicate_entry")
                        .increment();
                throw new ChatterValidationException("duplicate_entry",
                        "chatter entry already present for source " + source);
            }
            try {
                overlayStore.recordAdd(source, entry);
                loader.rebuildSnapshot();
            } catch (RuntimeException e) {
                meters.counter("chatter_edit_failures_total").increment();
                throw new ChatterEditException("chatter add failed for source " + source, e);
            }
        }
        meters.counter("chatter_edits_total", "source", source, "op", "add").increment();
        log.info("chatter add source={} key=({},{})", source, entry.managedObjectId(),
                entry.eventType());
        return entry;
    }

    /**
     * Remove a chatter entry. Validates the source + match key + presence, then persists +
     * hot-applies.
     */
    public void remove(String source, ChatterEntry entry) {
        requireKnownSource(source);
        requireValidKey(entry);
        synchronized (writeLock) {
            boolean present = registry.forSource(source).filterParams().chatterList().stream()
                    .anyMatch(e -> e.matchesKey(entry));
            if (!present) {
                meters.counter("chatter_api_rejected_total", "reason", "entry_not_present")
                        .increment();
                throw new ChatterValidationException("entry_not_present",
                        "chatter entry not present for source " + source);
            }
            try {
                overlayStore.recordRemove(source, entry);
                loader.rebuildSnapshot();
            } catch (RuntimeException e) {
                meters.counter("chatter_edit_failures_total").increment();
                throw new ChatterEditException("chatter remove failed for source " + source, e);
            }
        }
        meters.counter("chatter_edits_total", "source", source, "op", "remove").increment();
        log.info("chatter remove source={} key=({},{})", source, entry.managedObjectId(),
                entry.eventType());
    }

    private void requireKnownSource(String source) {
        if (!registry.hasSource(source) && !Ruleset.DEFAULT_SOURCE.equals(source)) {
            meters.counter("chatter_api_rejected_total", "reason", "unknown_source").increment();
            throw new ChatterValidationException("unknown_source",
                    "no configured ruleset for source " + source);
        }
    }

    private void requireValidKey(ChatterEntry entry) {
        if (entry == null || !entry.hasValidMatchKey()) {
            meters.counter("chatter_api_rejected_total", "reason", "malformed_entry").increment();
            throw new ChatterValidationException("malformed_entry",
                    "chatter entry requires non-blank managedObjectId and eventType");
        }
    }
}
