package com.acp.enrichment.chatter;

import com.acp.enrichment.ruleset.ChatterEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable per-source chatter overlay (design "Chatter edit persistence and hot-apply"). A small
 * JSON file recording operator chatter edits — adds and remove-tombstones — layered onto the
 * mounted YAML's {@code chatterList}. It is <b>configuration, not a domain datastore</b>: it
 * carries only chatter keys (no alarm payloads) and is single-owned by Enrichment.
 *
 * <p>Writes are atomic (write-temp-then-rename) so the change is durable and survives restart
 * (criterion 20). The store is not thread-safe by itself; {@link ChatterService} serializes all
 * mutations through a single writer lock.
 *
 * <p>File shape: {@code { "<source>": { "adds": [ChatterEntry...], "removes": [ChatterEntry...] } }}.
 */
public class ChatterOverlayStore {

    private final Path file;
    private final ObjectMapper mapper;
    // source -> (adds, removes); each list holds key-only ChatterEntry instances.
    private final Map<String, Overlay> bySource = new LinkedHashMap<>();

    private record Overlay(List<ChatterEntry> adds, List<ChatterEntry> removes) {
        Overlay() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    public ChatterOverlayStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /**
     * Load the overlay from disk. A missing file is normal (no edits yet). A corrupt/unreadable
     * file throws — the caller (loader) decides whether to treat it as fatal or degrade to base
     * YAML only (design error-handling: degrade, not fatal).
     *
     * @throws IOException on a read/parse failure
     */
    public synchronized void load() throws IOException {
        bySource.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            return;
        }
        ObjectNode root = (ObjectNode) mapper.readTree(bytes);
        var fields = root.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            String source = e.getKey();
            ObjectNode node = (ObjectNode) e.getValue();
            Overlay ov = new Overlay();
            readList(node, "adds", ov.adds());
            readList(node, "removes", ov.removes());
            bySource.put(source, ov);
        }
    }

    private void readList(ObjectNode node, String name, List<ChatterEntry> into) {
        if (node.has(name) && node.get(name).isArray()) {
            for (var n : node.get(name)) {
                into.add(new ChatterEntry(
                        text(n, "managedObjectId"), text(n, "eventType"),
                        text(n, "alarmType"), text(n, "promotedFrom")));
            }
        }
    }

    private String text(com.fasterxml.jackson.databind.JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    /** @param source the source @return the recorded add entries (provenance preserved). */
    public synchronized List<ChatterEntry> adds(String source) {
        Overlay ov = bySource.get(source);
        return ov == null ? List.of() : List.copyOf(ov.adds());
    }

    /** @param source the source @return the recorded remove-tombstone entries (key-only). */
    public synchronized List<ChatterEntry> removes(String source) {
        Overlay ov = bySource.get(source);
        return ov == null ? List.of() : List.copyOf(ov.removes());
    }

    /**
     * Record an add: append the entry to the source's adds (and clear any prior remove-tombstone
     * for the same key), then persist atomically.
     */
    public synchronized void recordAdd(String source, ChatterEntry entry) {
        Overlay ov = bySource.computeIfAbsent(source, s -> new Overlay());
        ov.removes().removeIf(r -> r.matchesKey(entry));
        if (ov.adds().stream().noneMatch(a -> a.matchesKey(entry))) {
            ov.adds().add(entry);
        }
        persist();
    }

    /**
     * Record a remove: drop any matching add, and record a remove-tombstone (so a base-YAML entry
     * stops being applied), then persist atomically.
     */
    public synchronized void recordRemove(String source, ChatterEntry entry) {
        Overlay ov = bySource.computeIfAbsent(source, s -> new Overlay());
        ov.adds().removeIf(a -> a.matchesKey(entry));
        if (ov.removes().stream().noneMatch(r -> r.matchesKey(entry))) {
            ov.removes().add(entry.keyOnly());
        }
        persist();
    }

    private void persist() {
        try {
            ObjectNode root = mapper.createObjectNode();
            for (var e : bySource.entrySet()) {
                ObjectNode node = mapper.createObjectNode();
                node.set("adds", toArray(e.getValue().adds()));
                node.set("removes", toArray(e.getValue().removes()));
                root.set(e.getKey(), node);
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = Path.of(file.toAbsolutePath() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist chatter overlay to " + file, e);
        }
    }

    private ArrayNode toArray(List<ChatterEntry> entries) {
        ArrayNode arr = mapper.createArrayNode();
        for (ChatterEntry c : entries) {
            ObjectNode n = mapper.createObjectNode();
            n.put("managedObjectId", c.managedObjectId());
            n.put("eventType", c.eventType());
            if (c.alarmType() != null) {
                n.put("alarmType", c.alarmType());
            }
            if (c.promotedFrom() != null) {
                n.put("promotedFrom", c.promotedFrom());
            }
            arr.add(n);
        }
        return arr;
    }
}
