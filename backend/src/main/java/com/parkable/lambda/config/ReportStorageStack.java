package com.parkable.lambda.config;

import com.parkable.repository.InMemoryRuleReportRepository;
import com.parkable.repository.RuleReportRepository;
import com.parkable.repository.postgres.PostgresRuleReportRepository;

import java.util.Objects;

/**
 * Composition root for report storage: Postgres when {@code PARKABLE_DB_URL}
 * is set, otherwise in-memory - same shape as {@link StorageStack}, but no
 * reflection needed since both implementations are owned in this same
 * feature (unlike StorageStack's Postgres side, which predates this class).
 */
public record ReportStorageStack(RuleReportRepository repository) {

    public ReportStorageStack {
        Objects.requireNonNull(repository, "repository");
    }

    public static ReportStorageStack from(EnvConfig config) {
        return config.dbUrl()
                .<ReportStorageStack>map(url -> new ReportStorageStack(new PostgresRuleReportRepository(url)))
                .orElseGet(() -> new ReportStorageStack(new InMemoryRuleReportRepository()));
    }
}
