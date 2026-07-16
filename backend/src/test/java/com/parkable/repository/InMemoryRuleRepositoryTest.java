package com.parkable.repository;

import com.parkable.extraction.dto.ExtractionEnvelope;
import org.junit.jupiter.api.Test;

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
}
