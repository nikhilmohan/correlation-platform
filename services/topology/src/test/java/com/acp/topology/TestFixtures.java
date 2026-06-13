package com.acp.topology;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads snapshot + vocabulary test fixtures from the classpath. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static String snapshot(String name) {
        return resource("snapshots/" + name);
    }

    public static String resource(String path) {
        try (InputStream in = TestFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture: " + path, e);
        }
    }
}
