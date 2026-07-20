package com.parkable.lambda.config;

import com.parkable.lambda.port.RuleLookup;
import com.parkable.repository.InMemoryRuleRepository;
import com.parkable.repository.RuleRepository;

import java.lang.reflect.Constructor;
import java.util.Objects;

/**
 * Composition root for storage: Postgres when {@code PARKABLE_DB_URL} is set
 * (plan decision D4), otherwise an in-memory stack so local dev and tests
 * never need a database or cloud credentials.
 *
 * <p>The Postgres side is loaded reflectively so this module compiles and
 * works standalone whether or not Codex's task X4
 * ({@code com.parkable.repository.postgres.PostgresRuleRepository}) has
 * landed yet. Per plan decision D6, that class must expose a public
 * constructor taking the JDBC URL as a single {@code String} and implement
 * both {@link RuleRepository} and {@link RuleLookup} on one instance.
 */
public record StorageStack(RuleRepository repository, RuleLookup lookup) {

    private static final String POSTGRES_REPOSITORY_CLASS =
            "com.parkable.repository.postgres.PostgresRuleRepository";

    public StorageStack {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(lookup, "lookup");
    }

    public static StorageStack from(EnvConfig config) {
        return config.dbUrl().map(StorageStack::postgres).orElseGet(StorageStack::inMemory);
    }

    private static StorageStack postgres(String jdbcUrl) {
        Object instance = instantiatePostgresRepository(jdbcUrl);
        return new StorageStack((RuleRepository) instance, (RuleLookup) instance);
    }

    private static StorageStack inMemory() {
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        return new StorageStack(repository, new InMemoryRuleLookup(repository));
    }

    private static Object instantiatePostgresRepository(String jdbcUrl) {
        try {
            Class<?> type = Class.forName(POSTGRES_REPOSITORY_CLASS);
            Constructor<?> constructor = type.getConstructor(String.class);
            return constructor.newInstance(jdbcUrl);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(POSTGRES_REPOSITORY_CLASS
                    + " is not on the classpath yet (Codex task X4, docs/plans/phase2-aws-backend.md). "
                    + "PARKABLE_DB_URL is set, so silently falling back to in-memory storage here "
                    + "would be the wrong failure mode for a configured deployment.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct " + POSTGRES_REPOSITORY_CLASS
                    + " — it must have a public constructor taking a single JDBC URL String (plan decision D6).", e);
        }
    }
}
