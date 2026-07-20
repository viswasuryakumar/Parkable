package com.parkable.lambda.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.lambda.port.StoredRule;
import com.parkable.repository.ExtractionRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void dbUrlConfiguredButPostgresRepositoryNotYetOnClasspathFailsFastWithAClearMessage() {
        // Documents the intended fail-fast behavior for a configured deployment
        // until Codex's X4 (PostgresRuleRepository) lands. Update/remove once it does.
        EnvConfig config = EnvConfig.from(Map.of("PARKABLE_DB_URL", "jdbc:postgresql://host/db"));

        assertThatThrownBy(() -> StorageStack.from(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgresRuleRepository");
    }
}
