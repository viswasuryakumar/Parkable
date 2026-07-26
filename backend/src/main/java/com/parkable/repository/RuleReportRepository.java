package com.parkable.repository;

/** Storage seam for user-flagged rule reports. */
public interface RuleReportRepository {
    void save(RuleReport report);
}
