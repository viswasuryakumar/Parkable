package com.parkable.repository.postgres;

import com.parkable.repository.RuleReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresRuleReportRepositoryTest {

    @Test
    void rejectsBlankJdbcUrl() {
        assertThatThrownBy(() -> new PostgresRuleReportRepository(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PARKABLE_DB_URL", matches = ".+")
    void savesAReportAgainstConfiguredPostgres() throws Exception {
        String jdbcUrl = System.getenv("PARKABLE_DB_URL");
        PostgresRuleReportRepository repository = new PostgresRuleReportRepository(jdbcUrl);
        UUID id = UUID.randomUUID();
        RuleReport report = new RuleReport(id, "test-rule-1", "test reason", "test-device",
                Instant.parse("2026-07-25T12:00:00Z"));

        try {
            repository.save(report);
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM rule_reports WHERE id = ?")) {
                delete.setObject(1, id);
                delete.executeUpdate();
            }
        }
    }
}
