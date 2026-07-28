package com.parkable.lambda.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deployment configuration resolved from environment variables (plan
 * decision D4). Secret values themselves — the DB password embedded in the
 * JDBC URL, the OpenRouter/Anthropic key — are injected by the SAM template
 * from SSM at deploy time; this class only reads whatever the process
 * environment already contains and never calls AWS directly.
 */
public record EnvConfig(Optional<String> dbUrl, Optional<String> openRouterApiKey, Optional<String> anthropicApiKey,
                         Optional<String> adminSecret) {

    public EnvConfig {
        Objects.requireNonNull(dbUrl, "dbUrl");
        Objects.requireNonNull(openRouterApiKey, "openRouterApiKey");
        Objects.requireNonNull(anthropicApiKey, "anthropicApiKey");
        Objects.requireNonNull(adminSecret, "adminSecret");
    }

    public static EnvConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static EnvConfig from(Map<String, String> env) {
        return new EnvConfig(
                nonBlank(env, "PARKABLE_DB_URL"),
                nonBlank(env, "OPENROUTER_API_KEY"),
                nonBlank(env, "ANTHROPIC_API_KEY"),
                nonBlank(env, "PARKABLE_ADMIN_SECRET"));
    }

    private static Optional<String> nonBlank(Map<String, String> env, String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
