package com.parkable.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/**
 * Structural validation: runs a raw extraction envelope against the canonical
 * JSON Schema (required fields, types, enums, conditional requirements).
 *
 * <p>Runs on the raw {@link JsonNode} BEFORE Jackson maps it to DTOs — a
 * type-mismatched document must produce a validation error (feeding the retry
 * path), not a mapping exception.
 */
public final class RuleJsonSchemaValidator {

    private static final String SCHEMA_RESOURCE = "/schema/parking-rule-schema.json";

    private final JsonSchema schema;

    public RuleJsonSchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = RuleJsonSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource missing from classpath: " + SCHEMA_RESOURCE);
            }
            this.schema = factory.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema resource " + SCHEMA_RESOURCE, e);
        }
    }

    public ValidationResult validate(JsonNode envelope) {
        Set<ValidationMessage> messages = schema.validate(envelope);
        if (messages.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.failure(messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList());
    }
}
