package com.parkable.repository;

import com.parkable.builder.RuleBuilder;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.factory.RuleFactory;
import com.parkable.model.Rule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRuleRepositoryTest {

    @Test
    void savesAndFindsAnExtractionWithItsReproducibilityTags() {
        RuleRepository repository = new InMemoryRuleRepository();
        ExtractionRecord record = record("scan-001", "camera-scan", "extractor-v1");

        repository.save(record);

        assertThat(repository.findByExtractionId("scan-001")).contains(record);
        assertThat(repository.findByExtractionId("missing")).isEmpty();
        assertThat(record.source()).isEqualTo("camera-scan");
        assertThat(record.parserVersion()).isEqualTo("extractor-v1");
        assertThat(record.gpsLocation()).contains(new ExtractionRecord.GpsCoordinates(37.7749, -122.4194));
    }

    @Test
    void replacesExistingRecordsByExtractionIdWithoutChangingInsertionOrder() {
        RuleRepository repository = new InMemoryRuleRepository();
        ExtractionRecord original = record("scan-001", "camera-scan", "extractor-v1");
        ExtractionRecord replacement = record("scan-001", "camera-scan", "extractor-v2");
        ExtractionRecord later = record("scan-002", "gov-etl", "etl-v1");

        repository.save(original);
        repository.save(later);
        repository.save(replacement);

        assertThat(repository.findAll()).containsExactly(replacement, later);
    }

    @Test
    void returnsAnUnmodifiableSnapshotOfStoredRecords() {
        RuleRepository repository = new InMemoryRuleRepository();
        repository.save(record("scan-001", "camera-scan", "extractor-v1"));

        List<ExtractionRecord> records = repository.findAll();

        assertThatThrownBy(() -> records.add(record("scan-002", "camera-scan", "extractor-v1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void supersedeMatchingRemovesOnlyTheSameSourceWithinRadiusAndMatchingContent() {
        RuleRepository repository = new InMemoryRuleRepository();
        Rule twoHourLimit = new RuleBuilder().timeLimit(Duration.ofMinutes(120)).withId("r1").build();
        ExtractionRecord staleCameraScan = recordWithRule("scan-001", "camera_scan", "extractor-v1", twoHourLimit);
        ExtractionRecord govData = recordWithRule("gov-001", "gov_data", "gov-v1", twoHourLimit);
        repository.save(staleCameraScan);
        repository.save(govData);

        // recordWithRule()'s fixed point is (37.7749, -122.4194); well within 25m of itself.
        repository.supersedeMatching("camera_scan", 37.7749, -122.4194, 25.0, List.of(twoHourLimit));

        assertThat(repository.findAll()).containsExactly(govData);
    }

    @Test
    void supersedeMatchingLeavesRecordsOutsideTheRadiusAlone() {
        RuleRepository repository = new InMemoryRuleRepository();
        Rule twoHourLimit = new RuleBuilder().timeLimit(Duration.ofMinutes(120)).withId("r1").build();
        ExtractionRecord farAway = recordWithRule("scan-001", "camera_scan", "extractor-v1", twoHourLimit);
        repository.save(farAway);

        // New York is nowhere near the fixed (37.7749, -122.4194) point.
        repository.supersedeMatching("camera_scan", 40.7128, -74.0060, 25.0, List.of(twoHourLimit));

        assertThat(repository.findAll()).containsExactly(farAway);
    }

    @Test
    void supersedeMatchingLeavesADifferentRegulationAloneEvenAtTheExactSamePoint() {
        // The real bug this guards: two genuinely different signs scanned
        // from the same spot must not delete each other just because they
        // share a location - only a re-read of the SAME regulation should.
        RuleRepository repository = new InMemoryRuleRepository();
        Rule noParkingMonWed = new RuleBuilder().noParking().withId("r1")
                .onDays(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY).anyTime().build();
        Rule twoHourLimit = new RuleBuilder().timeLimit(Duration.ofMinutes(120)).withId("r2").build();
        ExtractionRecord existingSign = recordWithRule("scan-001", "camera_scan", "extractor-v1", noParkingMonWed);
        repository.save(existingSign);

        repository.supersedeMatching("camera_scan", 37.7749, -122.4194, 25.0, List.of(twoHourLimit));

        assertThat(repository.findAll()).containsExactly(existingSign);
    }

    @Test
    void rejectsInvalidGpsCoordinates() {
        assertThatThrownBy(() -> new ExtractionRecord.GpsCoordinates(91, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
        assertThatThrownBy(() -> new ExtractionRecord.GpsCoordinates(0, -181))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
    }

    private static ExtractionRecord record(String extractionId, String source, String parserVersion) {
        return new ExtractionRecord(
                new ExtractionEnvelope(extractionId, source, null, null, parserVersion,
                        "2026-07-16T18:00:00Z", "camera", 0.95, null, null, null, List.of()),
                "photos/" + extractionId + ".jpg",
                source,
                parserVersion,
                Optional.of(new ExtractionRecord.GpsCoordinates(37.7749, -122.4194)),
                Instant.parse("2026-07-16T18:00:00Z"));
    }

    private static ExtractionRecord recordWithRule(
            String extractionId, String source, String parserVersion, Rule rule) {
        RuleDto dto = RuleFactory.toDto(rule);
        return new ExtractionRecord(
                new ExtractionEnvelope(extractionId, source, null, null, parserVersion,
                        "2026-07-16T18:00:00Z", "camera", 0.95, null, null, null, List.of(dto)),
                "photos/" + extractionId + ".jpg",
                source,
                parserVersion,
                Optional.of(new ExtractionRecord.GpsCoordinates(37.7749, -122.4194)),
                Instant.parse("2026-07-16T18:00:00Z"));
    }
}
