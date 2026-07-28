package com.parkable.lambda.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvConfigTest {

    @Test
    void emptyEnvironmentYieldsEmptyOptionals() {
        EnvConfig config = EnvConfig.from(Map.of());

        assertThat(config.dbUrl()).isEmpty();
        assertThat(config.openRouterApiKey()).isEmpty();
        assertThat(config.anthropicApiKey()).isEmpty();
        assertThat(config.adminSecret()).isEmpty();
    }

    @Test
    void blankValuesAreTreatedAsAbsent() {
        EnvConfig config = EnvConfig.from(Map.of(
                "PARKABLE_DB_URL", "   ", "OPENROUTER_API_KEY", "", "ANTHROPIC_API_KEY", "",
                "PARKABLE_ADMIN_SECRET", ""));

        assertThat(config.dbUrl()).isEmpty();
        assertThat(config.openRouterApiKey()).isEmpty();
        assertThat(config.anthropicApiKey()).isEmpty();
        assertThat(config.adminSecret()).isEmpty();
    }

    @Test
    void presentValuesArePassedThrough() {
        EnvConfig config = EnvConfig.from(Map.of(
                "PARKABLE_DB_URL", "jdbc:postgresql://host/db",
                "OPENROUTER_API_KEY", "sk-or-test",
                "ANTHROPIC_API_KEY", "sk-ant-test",
                "PARKABLE_ADMIN_SECRET", "hunter2"));

        assertThat(config.dbUrl()).contains("jdbc:postgresql://host/db");
        assertThat(config.openRouterApiKey()).contains("sk-or-test");
        assertThat(config.anthropicApiKey()).contains("sk-ant-test");
        assertThat(config.adminSecret()).contains("hunter2");
    }
}
