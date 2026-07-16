package com.parkable.repository;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam for extracted parking rules. Phase 2 can replace this with a
 * durable repository without changing callers that persist scan outcomes.
 */
public interface RuleRepository {

    void save(ExtractionRecord record);

    Optional<ExtractionRecord> findByExtractionId(String extractionId);

    List<ExtractionRecord> findAll();
}
