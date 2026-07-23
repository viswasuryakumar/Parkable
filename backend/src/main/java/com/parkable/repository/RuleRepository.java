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

    /**
     * Removes existing records from {@code source} within {@code radiusMeters}
     * of a point. Every scan gets a fresh extraction_id (Phase 1's
     * reproducibility contract), so without this, re-scanning the same
     * physical sign never overwrites anything — it just accumulates another
     * independent set of rows forever, and {@code /nearby}/{@code /check}
     * then have to reconcile multiple, possibly-contradictory readings of
     * what should be one sign (found live: the same "no loading" sign,
     * scanned twice, produced two different sets of hours). Callers should
     * invoke this immediately before {@link #save} with the same source, so
     * a fresh scan supersedes the stale one instead of coexisting with it.
     * Default no-op is wrong for any implementation meant to serve /check —
     * only acceptable for a throwaway/test double.
     */
    default void supersedeNearby(String source, double latitude, double longitude, double radiusMeters) {}

    Optional<ExtractionRecord> findByExtractionId(String extractionId);

    List<ExtractionRecord> findAll();
}
