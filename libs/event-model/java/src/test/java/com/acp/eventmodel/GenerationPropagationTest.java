package com.acp.eventmodel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.jsonschema2pojo.DefaultGenerationConfig;
import org.jsonschema2pojo.GenerationConfig;
import org.jsonschema2pojo.Jsonschema2Pojo;
import org.jsonschema2pojo.RuleLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Criterion 2 (Java side): single-source propagation.
 *
 * <p>Adding an optional field to a copy of a payload schema and regenerating yields the new field
 * on the Java POJO with NO hand edits — the binding is generated from the schema. This drives the
 * SAME generator the build uses ({@code jsonschema2pojo}) over a temp schema dir and inspects the
 * generated Java source. Mirrors the Python {@code test_generation.py}.
 */
class GenerationPropagationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void addedSchemaFieldAppearsInPojo(@TempDir Path tmp) throws Exception {
        // 1. Copy the AlarmEvent schema (and the common managedObjectId schema it $refs,
        //    preserving the relative ../common path) and add a brand-new optional field.
        Path schemaRoot = Fixtures.dir().getParent();
        Path payloadsDir = Files.createDirectories(tmp.resolve("payloads"));
        Path commonDir = Files.createDirectories(tmp.resolve("common"));
        Files.copy(schemaRoot.resolve("common/managedObjectId.schema.json"),
                commonDir.resolve("managedObjectId.schema.json"));

        ObjectNode schema = (ObjectNode) MAPPER.readTree(
                schemaRoot.resolve("payloads/AlarmEvent.schema.json").toFile());
        ObjectNode props = (ObjectNode) schema.get("properties");
        ObjectNode newField = MAPPER.createObjectNode();
        newField.put("type", "string");
        newField.put("description", "A new optional field added to the schema.");
        props.set("acknowledgedBy", newField);          // optional: not added to `required`
        Path schemaFile = payloadsDir.resolve("AlarmEvent.schema.json");
        Files.writeString(schemaFile, MAPPER.writeValueAsString(schema), StandardCharsets.UTF_8);

        // 2. Regenerate POJOs from the edited schema (no manual edits to any binding source).
        Path outDir = Files.createDirectories(tmp.resolve("gen"));
        regenerate(payloadsDir.toFile(), outDir.toFile());

        // 3. The new field is present in the generated POJO.
        Path generated = outDir.resolve("gen/AlarmEvent.java");
        assertTrue(Files.exists(generated), "POJO regenerated at " + generated);
        String source = Files.readString(generated, StandardCharsets.UTF_8);
        assertTrue(source.contains("acknowledgedBy"),
                "the newly-added schema field must appear in the regenerated POJO");

        // Control: a field that was never added is absent (the generator reflects the schema, not
        // hand edits).
        assertFalse(source.contains("notInSchemaField"));
    }

    private static void regenerate(File schemaDir, File outDir) throws Exception {
        GenerationConfig config = new DefaultGenerationConfig() {
            @Override
            public Iterator<java.net.URL> getSource() {
                try {
                    return java.util.List.of(schemaDir.toURI().toURL()).iterator();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public File getTargetDirectory() {
                return outDir;
            }

            @Override
            public String getTargetPackage() {
                return "gen";
            }

            @Override
            public boolean isUseTitleAsClassname() {
                return true;
            }
        };
        Jsonschema2Pojo.generate(config, new SilentLogger());
    }

    /** Minimal {@link RuleLogger} — generation logs are noise for this test. */
    private static final class SilentLogger implements RuleLogger {
        @Override public void debug(String msg) { }
        @Override public void error(String msg) { }
        @Override public void error(String msg, Throwable e) { }
        @Override public void info(String msg) { }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isErrorEnabled() { return false; }
        @Override public boolean isInfoEnabled() { return false; }
        @Override public boolean isTraceEnabled() { return false; }
        @Override public boolean isWarnEnabled() { return false; }
        @Override public void trace(String msg) { }
        @Override public void warn(String msg, Throwable e) { }
        @Override public void warn(String msg) { }
    }
}
