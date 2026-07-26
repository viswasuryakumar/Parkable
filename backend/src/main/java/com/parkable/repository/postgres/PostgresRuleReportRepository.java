package com.parkable.repository.postgres;

import com.parkable.repository.RuleReport;
import com.parkable.repository.RuleReportRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Properties;

/**
 * Postgres storage for {@link RuleReport}. Reuses
 * {@link PostgresRuleRepository#parse} (package-private, same package) for
 * libpq-style credential parsing rather than duplicating that logic - this
 * table needs nothing else from that class (no geometry, no upsert).
 */
public final class PostgresRuleReportRepository implements RuleReportRepository {

    private static final String INSERT_REPORT = """
            INSERT INTO rule_reports (id, rule_id, reason, device_id, reported_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final String jdbcUrl;

    public PostgresRuleReportRepository(String jdbcUrl) {
        this.jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
    }

    @Override
    public void save(RuleReport report) {
        Objects.requireNonNull(report, "report");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_REPORT)) {
            statement.setObject(1, report.id());
            statement.setString(2, report.ruleId());
            statement.setString(3, report.reason());
            statement.setString(4, report.deviceId());
            statement.setTimestamp(5, Timestamp.from(report.reportedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres failed to save rule report", e);
        }
    }

    private Connection openConnection() throws SQLException {
        PostgresRuleRepository.ParsedConnection parsed = PostgresRuleRepository.parse(jdbcUrl);
        Properties properties = new Properties();
        properties.setProperty("prepareThreshold", "0");
        if (parsed.user() != null) {
            properties.setProperty("user", parsed.user());
            properties.setProperty("password", parsed.password());
        }
        return DriverManager.getConnection(parsed.url(), properties);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
