package com.parkable.repository.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.extraction.ClaudeVisionExtractor;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.lambda.port.RuleLookup;
import com.parkable.lambda.port.StoredRule;
import com.parkable.model.NoParkingRule;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.RuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresRuleRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exposesOnlyTheD6JdbcUrlConstructorAndBothRepositoryPorts() {
        Constructor<?>[] constructors = PostgresRuleRepository.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(String.class);
        assertThat(RuleRepository.class.isAssignableFrom(PostgresRuleRepository.class)).isTrue();
        assertThat(RuleLookup.class.isAssignableFrom(PostgresRuleRepository.class)).isTrue();
    }

    @Test
    void mapsStoredJsonBackToTheDomainRuleAndKeepsProvenance() throws IOException {
        ExtractionRecord record = record();
        String json = PostgresRuleRepository.encodeRule(record, record.envelope().rules().getFirst());

        StoredRule stored = PostgresRuleRepository.toStoredRule(json, record.source(), record.parserVersion());

        assertThat(stored.rule()).isInstanceOf(NoParkingRule.class);
        assertThat(stored.rule().metadata().ruleId()).isEqualTo("sign-rule-1");
        assertThat(stored.source()).isEqualTo("camera_scan");
        assertThat(stored.parserVersion()).isEqualTo(ClaudeVisionExtractor.PARSER_VERSION);
        assertThat(json).contains("_parkable", "photo-1.jpg", record.extractionId());
    }

    @Test
    void usesPreparedStatementSqlWithParserFilterAndLongitudeFirstPostgisPoints() {
        assertThat(PostgresRuleRepository.UPSERT_RULE)
                .contains("ST_MakePoint(?, ?)", "ON CONFLICT (id)", "?::jsonb");
        assertThat(PostgresRuleRepository.FIND_WITHIN)
                .contains("parser_version = ?", "ST_DWithin", "ST_Distance", "ST_MakePoint(?, ?)");
    }

    @Test
    void rejectsInvalidConnectionUrlAndLookupArgumentsBeforeDatabaseWork() {
        assertThatThrownBy(() -> new PostgresRuleRepository(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl");

        PostgresRuleRepository repository = new PostgresRuleRepository("jdbc:postgresql://example.invalid/db");
        assertThatThrownBy(() -> repository.findWithin(91, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
        assertThatThrownBy(() -> repository.findWithin(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radiusMeters");
    }

    @Test
    void parsesEmbeddedLibpqStyleCredentialsForTheJdbcDriver() {
        // The pg JDBC driver does not understand libpq-style "user:pass@host"
        // credentials in the URL authority - it would try to resolve the
        // whole "user:pass@host" string as one literal hostname. Every
        // hosted Postgres provider (Supabase included) hands out connection
        // strings in exactly that shape, so this is what makes them usable.
        // The password's own "@" proves the split lands on the LAST "@".
        PostgresRuleRepository.ParsedConnection parsed = PostgresRuleRepository.parse(
                "jdbc:postgresql://myuser:my@pass@aws-0-us-west-1.pooler.supabase.com:6543/postgres");

        assertThat(parsed.url()).isEqualTo("jdbc:postgresql://aws-0-us-west-1.pooler.supabase.com:6543/postgres");
        assertThat(parsed.user()).isEqualTo("myuser");
        assertThat(parsed.password()).isEqualTo("my@pass");
    }

    @Test
    void leavesAlreadyStandardJdbcUrlsUnchanged() {
        PostgresRuleRepository.ParsedConnection parsed =
                PostgresRuleRepository.parse("jdbc:postgresql://host:5432/db?user=u&password=p");

        assertThat(parsed.url()).isEqualTo("jdbc:postgresql://host:5432/db?user=u&password=p");
        assertThat(parsed.user()).isNull();
        assertThat(parsed.password()).isNull();
    }

    @Test
    void neverLeaksCredentialsFromTheJdbcUrlWhenAConnectionFails() {
        // example.invalid (RFC 2606) never resolves, forcing a real connection
        // failure without a live database. A production incident showed the
        // JDBC driver's own exception embeds the full URL, credentials
        // included, in its message — assert that string never survives into
        // any exception in the chain this repository throws.
        String secret = "s3cr3t-password-should-never-leak";
        PostgresRuleRepository repository =
                new PostgresRuleRepository("jdbc:postgresql://user:" + secret + "@example.invalid/db");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> repository.findWithin(37.7749, -122.4194, 25));

        assertThat(thrown).isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(t.getMessage()).as("exception in chain: " + t.getClass()).doesNotContain(secret);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PARKABLE_DB_URL", matches = ".+")
    void savesAndFindsARuleAgainstConfiguredPostgres() throws Exception {
        String jdbcUrl = System.getenv("PARKABLE_DB_URL");
        ExtractionRecord record = record();
        RuleDto rule = record.envelope().rules().getFirst();
        PostgresRuleRepository repository = new PostgresRuleRepository(jdbcUrl);
        UUID id = PostgresRuleRepository.stableRuleId(record.extractionId(), rule.ruleId());

        try {
            repository.save(record);

            List<StoredRule> found = repository.findWithin(37.7749, -122.4194, 25);
            assertThat(found).extracting(item -> item.rule().metadata().ruleId()).contains(rule.ruleId());
            assertThat(repository.findByExtractionId(record.extractionId())).contains(record);
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM rules WHERE id = ?")) {
                delete.setObject(1, id);
                delete.executeUpdate();
            }
        }
    }

    private static ExtractionRecord record() throws IOException {
        ExtractionEnvelope fixture = MAPPER.readValue(
                Files.readString(Path.of("src/test/resources/fixtures/sign.json")), ExtractionEnvelope.class);
        ExtractionEnvelope envelope = new ExtractionEnvelope(
                "postgres-test-" + UUID.randomUUID(), fixture.source(), fixture.city(), fixture.state(),
                ClaudeVisionExtractor.PARSER_VERSION, fixture.ingestionTimestamp(), fixture.extractionMethod(),
                fixture.confidence(), fixture.coverageCompleteness(), fixture.notes(), fixture.rawText(), fixture.rules());
        return new ExtractionRecord(
                envelope, "photo-1.jpg", "camera_scan", ClaudeVisionExtractor.PARSER_VERSION,
                Optional.of(new ExtractionRecord.GpsCoordinates(37.7749, -122.4194)),
                Instant.parse("2026-07-20T22:00:00Z"));
    }
}
