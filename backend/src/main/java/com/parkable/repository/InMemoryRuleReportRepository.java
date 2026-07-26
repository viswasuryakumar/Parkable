package com.parkable.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class InMemoryRuleReportRepository implements RuleReportRepository {

    private final List<RuleReport> reports = new ArrayList<>();

    @Override
    public void save(RuleReport report) {
        reports.add(Objects.requireNonNull(report, "report"));
    }

    public List<RuleReport> findAll() {
        return List.copyOf(reports);
    }
}
