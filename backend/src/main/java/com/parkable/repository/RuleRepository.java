package com.parkable.repository;

import com.parkable.model.Rule;

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
     * Deletes existing {@code source} rules within {@code radiusMeters} of a
     * point, but ONLY the ones that {@link Rule#describesSameRegulation}
     * one of {@code newRules} — proximity alone is not enough to assume
     * "this is the same sign being re-read." Distinct regulatory signs
     * commonly stand within the same handful of metres of each other (a
     * loading zone ending where a permit zone begins, both ends of a short
     * block), and GPS accuracy is often no better than that spacing, so an
     * earlier proximity-only version of this method deleted a real,
     * different 3-panel sign the first time two signs were scanned from
     * nearby positions. Content matching narrows "supersede" to what it's
     * actually meant to catch: a genuine re-read of the SAME regulation
     * (identical day pattern, time windows, and type-specific detail),
     * leaving anything that reads as a different regulation untouched so it
     * survives alongside the new scan instead of being silently destroyed.
     * Callers should invoke this immediately before {@link #save} with the
     * same source and the about-to-be-saved rules. Default no-op is wrong
     * for any implementation meant to serve /check — only acceptable for a
     * throwaway/test double.
     */
    default void supersedeMatching(String source, double latitude, double longitude,
                                    double radiusMeters, List<Rule> newRules) {}

    Optional<ExtractionRecord> findByExtractionId(String extractionId);

    List<ExtractionRecord> findAll();
}
