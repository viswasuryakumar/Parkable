package com.parkable.repository;

import java.util.List;

/** Storage seam for user-flagged rule reports. */
public interface RuleReportRepository {
    void save(RuleReport report);

    /** Most recent first, for the admin review screen. */
    List<RuleReport> list();
}
