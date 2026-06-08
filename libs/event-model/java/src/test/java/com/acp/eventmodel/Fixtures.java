package com.acp.eventmodel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Locates and reads the SAME golden fixtures the Python binding's tests read:
 * {@code libs/event-model/schema/fixtures/*.json}. These files are owned by the schema (not by
 * either binding) and are the cross-binding contract anchor (criterion 1).
 */
final class Fixtures {

    /** The nine payload type names — one golden fixture each. */
    static final List<String> ALL_TYPES = List.of(
            "AlarmEvent", "TopologyChangedEvent", "TrailsBuiltEvent", "CodebookGeneratedEvent",
            "TransactionEvent", "PatternMinedEvent", "PatternDiscoveredEvent", "PatternApprovedEvent",
            "CorrelationResultEvent");

    private Fixtures() {
    }

    /** @return the {@code schema/fixtures} directory, resolved relative to the Gradle project dir. */
    static Path dir() {
        // Gradle runs tests with the project directory (libs/event-model/java) as the working dir.
        Path candidate = Paths.get("..", "schema", "fixtures").toAbsolutePath().normalize();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Fallback for IDE runs: walk up from the working dir to find libs/event-model/schema.
        Path here = Paths.get("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path f = p.resolve("libs/event-model/schema/fixtures");
            if (Files.isDirectory(f)) {
                return f;
            }
        }
        throw new IllegalStateException("could not locate schema/fixtures from " + here);
    }

    /** @return the raw JSON text of the golden fixture for {@code type}. */
    static String read(String type) {
        Path path = dir().resolve(type + ".json");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + path, e);
        }
    }
}
