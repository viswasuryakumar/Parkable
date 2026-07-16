package com.parkable.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleJsonSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleJsonSchemaValidator validator = new RuleJsonSchemaValidator();

    private static ObjectNode validEnvelope() {
        try {
            return (ObjectNode) MAPPER.readTree(
                    Files.readString(Path.of("src/test/resources/fixtures/sign.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void goldenFixturePassesSchemaValidation() {
        ValidationResult result = validator.validate(validEnvelope());

        assertThat(result.valid()).as("errors: " + result.errors()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void missingRuleTypeFails() {
        ObjectNode envelope = validEnvelope();
        ((ObjectNode) envelope.get("rules").get(0)).remove("type");

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("type"));
    }

    @Test
    void timeLimitWithoutDurationFails() {
        ObjectNode envelope = validEnvelope();
        ObjectNode rule = (ObjectNode) envelope.get("rules").get(0);
        rule.put("type", "time_limit"); // conditional requirement: needs restriction.duration_minutes

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void illegalEnumValueFails() {
        ObjectNode envelope = validEnvelope();
        envelope.put("source", "crystal_ball");

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void emptyRulesArrayFails() {
        ObjectNode envelope = validEnvelope();
        envelope.putArray("rules");

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void typeMismatchIsAValidationErrorNotACrash() {
        ObjectNode envelope = validEnvelope();
        envelope.put("confidence", "very high"); // string where number expected

        ValidationResult result = validator.validate(envelope);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void invalidFixtureFileFailsValidation() throws IOException {
        JsonNode envelope = MAPPER.readTree(
                Files.readString(Path.of("src/test/resources/fixtures/invalid-missing-type.json")));

        assertThat(validator.validate(envelope).valid()).isFalse();
    }
}
