package com.parkable.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class InMemoryRuleReportRepository implements RuleReportRepository {

    private final List<RuleReport> reports = new ArrayList<>();

    @Override
    public void save(RuleReport report) {
        reports.add(Objects.requireNonNull(report, "report"));
    }

    @Override
    public List<RuleReport> list() {
        List<RuleReport> copy = new ArrayList<>(reports);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    public List<RuleReport> findAll() {
        return List.copyOf(reports);
    }
}
