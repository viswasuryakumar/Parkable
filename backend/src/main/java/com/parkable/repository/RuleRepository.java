package com.parkable.repository;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam for extracted parking rules. Phase 2 can replace this with a
 * durable repository without changing callers that persist scan outcomes.
 */
public interface RuleRepository {

    void save(ExtractionRecord record);

    /**
     * Saves many records as one unit of work. The default just repeats
     * {@link #save}, which is correct for every implementation but wasteful
     * for one that opens a fresh connection per call (Postgres, bulk ETL) —
     * that implementation should override this to reuse one connection
     * across the whole batch instead.
     */
    default void saveAll(List<ExtractionRecord> records) {
        records.forEach(this::save);
    }

    Optional<ExtractionRecord> findByExtractionId(String extractionId);

    List<ExtractionRecord> findAll();
}
