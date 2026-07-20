package com.parkable.lambda.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvConfigTest {

    @Test
    void emptyEnvironmentYieldsEmptyOptionals() {
        EnvConfig config = EnvConfig.from(Map.of());

        assertThat(config.dbUrl()).isEmpty();
        assertThat(config.anthropicApiKey()).isEmpty();
    }

    @Test
    void blankValuesAreTreatedAsAbsent() {
        EnvConfig config = EnvConfig.from(Map.of("PARKABLE_DB_URL", "   ", "ANTHROPIC_API_KEY", ""));

        assertThat(config.dbUrl()).isEmpty();
        assertThat(config.anthropicApiKey()).isEmpty();
    }

    @Test
    void presentValuesArePassedThrough() {
        EnvConfig config = EnvConfig.from(Map.of(
                "PARKABLE_DB_URL", "jdbc:postgresql://host/db",
                "ANTHROPIC_API_KEY", "sk-ant-test"));

        assertThat(config.dbUrl()).contains("jdbc:postgresql://host/db");
        assertThat(config.anthropicApiKey()).contains("sk-ant-test");
    }
}
