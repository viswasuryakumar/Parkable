package com.parkable.lambda.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.lambda.port.StoredRule;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.postgres.PostgresRuleRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StorageStackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ExtractionEnvelope signFixture() throws IOException {
        return MAPPER.readValue(
                Files.readString(Path.of("src/test/resources/fixtures/sign.json")),
                ExtractionEnvelope.class);
    }

    @Test
    void defaultConfigBuildsAWorkingInMemoryStack() throws IOException {
        StorageStack stack = StorageStack.from(EnvConfig.from(Map.of()));

        stack.repository().save(new ExtractionRecord(
                signFixture(), "photo-1", "camera_scan", "test-v1",
                Optional.of(new ExtractionRecord.GpsCoordinates(37.7749, -122.4194)),
                Instant.parse("2026-07-14T18:04:00Z")));

        List<StoredRule> nearby = stack.lookup().findWithin(37.7749, -122.4194, 25.0);
        assertThat(nearby).hasSize(1);
        assertThat(stack.repository().findAll()).hasSize(1);
    }

    @Test
    void dbUrlConfiguredLoadsThePostgresRepositoryReflectivelyByTheD6Constructor() {
        // A fake host is fine here: the JDBC URL is only stored at construction
        // time (see PostgresRuleRepository), no connection is opened until a
        // read/write method actually runs. This proves the reflective wiring
        // (Class.forName + getConstructor(String.class)) matches X4's real
        // constructor, not just that reflection would work in theory.
        EnvConfig config = EnvConfig.from(Map.of("PARKABLE_DB_URL", "jdbc:postgresql://example.invalid/db"));

        StorageStack stack = StorageStack.from(config);

        assertThat(stack.repository()).isInstanceOf(PostgresRuleRepository.class);
        assertThat(stack.repository()).isSameAs(stack.lookup());
    }
}
