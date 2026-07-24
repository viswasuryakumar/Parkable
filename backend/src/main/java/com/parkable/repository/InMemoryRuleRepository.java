package com.parkable.repository;

import com.parkable.factory.RuleFactory;
import com.parkable.model.Rule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 1 repository implementation. Linked insertion order makes retrieval
 * deterministic for local CLI traces while preserving the Phase 2 seam.
 */
public final class InMemoryRuleRepository implements RuleRepository {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final Map<String, ExtractionRecord> recordsByExtractionId = new LinkedHashMap<>();

    @Override
    public void save(ExtractionRecord record) {
        ExtractionRecord nonNullRecord = Objects.requireNonNull(record, "record");
        recordsByExtractionId.put(nonNullRecord.extractionId(), nonNullRecord);
    }

    @Override
    public void supersedeMatching(String source, double latitude, double longitude,
                                   double radiusMeters, List<Rule> newRules) {
        // Storage here is per-scan (whole ExtractionRecord), not per-rule
        // like Postgres, so a content match against any of the record's
        // rules supersedes the whole record - fine for this dev/test-only
        // implementation, where a record is always one small scan's rules.
        recordsByExtractionId.values().removeIf(record ->
                source.equals(record.source())
                        && record.gpsLocation()
                                .filter(gps -> metersBetween(gps.latitude(), gps.longitude(), latitude, longitude)
                                        <= radiusMeters)
                                .isPresent()
                        && RuleFactory.fromEnvelope(record.envelope()).stream()
                                .anyMatch(existing -> newRules.stream().anyMatch(existing::describesSameRegulation)));
    }

    @Override
    public Optional<ExtractionRecord> findByExtractionId(String extractionId) {
        return Optional.ofNullable(recordsByExtractionId.get(extractionId));
    }

    @Override
    public List<ExtractionRecord> findAll() {
        return List.copyOf(recordsByExtractionId.values());
    }

    private static double metersBetween(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
