package com.parkable.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the canonical rule schema once per extractor instance construction. */
final class ExtractionSchema {

    private static final String SCHEMA_RESOURCE = "/schema/parking-rule-schema.json";

    private ExtractionSchema() {}

    static String loadJson() {
        try (InputStream in = ExtractionSchema.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource missing from classpath: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema resource " + SCHEMA_RESOURCE, e);
        }
    }
}
