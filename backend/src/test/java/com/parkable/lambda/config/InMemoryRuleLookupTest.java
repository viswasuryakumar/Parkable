package com.parkable.lambda.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.lambda.port.StoredRule;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.InMemoryRuleRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRuleLookupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ExtractionEnvelope fixture(String name) throws IOException {
        return MAPPER.readValue(
                Files.readString(Path.of("src/test/resources/fixtures/" + name)),
                ExtractionEnvelope.class);
    }

    @Test
    void returnsStoredRuleWithProvenanceWhenWithinRadius() throws IOException {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        InMemoryRuleLookup lookup = new InMemoryRuleLookup(repository);
        repository.save(new ExtractionRecord(fixture("sign.json"), "near.jpg", "camera_scan", "v1",
                Optional.of(new ExtractionRecord.GpsCoordinates(37.7793, -122.4193)),
                Instant.parse("2026-07-14T18:04:00Z")));

        List<StoredRule> found = lookup.findWithin(37.7793, -122.4193, 25.0);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().rule().metadata().ruleId()).isEqualTo("sign-rule-1");
        assertThat(found.getFirst().source()).isEqualTo("camera_scan");
        assertThat(found.getFirst().parserVersion()).isEqualTo("v1");
    }

    @Test
    void excludesRecordsOutsideRadius() throws IOException {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        InMemoryRuleLookup lookup = new InMemoryRuleLookup(repository);
        // ~9km from the query point below — well outside any check/nearby radius.
        repository.save(new ExtractionRecord(fixture("sign.json"), "far.jpg", "camera_scan", "v1",
                Optional.of(new ExtractionRecord.GpsCoordinates(37.8716, -122.2727)),
                Instant.parse("2026-07-14T18:04:00Z")));

        List<StoredRule> found = lookup.findWithin(37.7793, -122.4193, 25.0);

        assertThat(found).isEmpty();
    }

    @Test
    void excludesRecordsWithNoGpsRecorded() throws IOException {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        InMemoryRuleLookup lookup = new InMemoryRuleLookup(repository);
        repository.save(new ExtractionRecord(fixture("sign.json"), "no-gps.jpg", "camera_scan", "v1",
                Optional.empty(), Instant.parse("2026-07-14T18:04:00Z")));

        List<StoredRule> found = lookup.findWithin(37.7793, -122.4193, 1_000_000.0);

        assertThat(found).isEmpty();
    }
}
