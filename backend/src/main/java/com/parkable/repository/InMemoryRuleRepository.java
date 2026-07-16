package com.parkable.repository;

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

    private final Map<String, ExtractionRecord> recordsByExtractionId = new LinkedHashMap<>();

    @Override
    public void save(ExtractionRecord record) {
        ExtractionRecord nonNullRecord = Objects.requireNonNull(record, "record");
        recordsByExtractionId.put(nonNullRecord.extractionId(), nonNullRecord);
    }

    @Override
    public Optional<ExtractionRecord> findByExtractionId(String extractionId) {
        return Optional.ofNullable(recordsByExtractionId.get(extractionId));
    }

    @Override
    public List<ExtractionRecord> findAll() {
        return List.copyOf(recordsByExtractionId.values());
    }
}
