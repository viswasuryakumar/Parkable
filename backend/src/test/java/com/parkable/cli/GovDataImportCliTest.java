package com.parkable.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.datasource.GovDataFeed;
import com.parkable.datasource.MappedRule;
import com.parkable.datasource.NycSignMapper;
import com.parkable.model.DirectionalModifier;
import com.parkable.model.HolidayPolicy;
import com.parkable.model.NoParkingRule;
import com.parkable.model.RuleMetadata;
import com.parkable.model.SpecificDays;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.InMemoryRuleRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the import loop and source registry entirely offline — no
 * GovDataFeed here talks to the network, matching Phase 2.5's DoD that
 * default {@code mvn test} never makes a live call.
 */
class GovDataImportCliTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static GovDataFeed feedOf(String... records) {
        List<JsonNode> nodes = List.of(records).stream().map(GovDataImportCliTest::json).toList();
        return () -> nodes.iterator();
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void importsAConfidentlyMappedRecordWithItsMappedLocation() {
        GovDataFeed feed = feedOf(
                "{\"order_number\":\"S-42\",\"sign_description\":\"NO PARKING MON-FRI 8AM-6PM\","
                        + "\"sign_x_coord\":\"982004\",\"sign_y_coord\":\"204840\"}");
        GovDataImportCli.GovDataSource source = new GovDataImportCli.GovDataSource(
                "nyc-test", feed, new NycSignMapper(), "New York City", "NY");
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        int exitCode = GovDataImportCli.run(List.of(source), repository, clock, new PrintStream(new ByteArrayOutputStream()));

        assertThat(exitCode).isEqualTo(0);
        List<ExtractionRecord> saved = repository.findAll();
        assertThat(saved).hasSize(1);
        ExtractionRecord record = saved.getFirst();
        assertThat(record.source()).isEqualTo("gov_data");
        assertThat(record.parserVersion()).isEqualTo("gov-nyc-mapper-v1");
        assertThat(record.gpsLocation()).isPresent();
        assertThat(record.gpsLocation().get().latitude()).isCloseTo(40.7289, org.assertj.core.data.Offset.offset(0.001));
        assertThat(record.envelope().rules()).hasSize(1);
        assertThat(record.envelope().rules().getFirst().ruleId()).isEqualTo("nyc:nfid-uabd:S-42");
    }

    @Test
    void reRunningTheSameSourceOverwritesRatherThanDuplicates() {
        GovDataFeed feed = feedOf(
                "{\"order_number\":\"S-42\",\"sign_description\":\"NO PARKING MON-FRI 8AM-6PM\","
                        + "\"sign_x_coord\":\"982004\",\"sign_y_coord\":\"204840\"}");
        GovDataImportCli.GovDataSource source = new GovDataImportCli.GovDataSource(
                "nyc-test", feed, new NycSignMapper(), "New York City", "NY");
        InMemoryRuleRepository repository = new InMemoryRuleRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        GovDataImportCli.run(List.of(source), repository, clock, new PrintStream(new ByteArrayOutputStream()));
        GovDataImportCli.run(List.of(source), repository, clock, new PrintStream(new ByteArrayOutputStream()));

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void recordsThatFailToMapAreCountedAsDroppedNotImported() {
        GovDataFeed feed = feedOf(
                "{\"order_number\":\"S-99\",\"sign_description\":\"PRESS BUTTON FOR SIGNAL\"}");
        GovDataImportCli.GovDataSource source = new GovDataImportCli.GovDataSource(
                "nyc-test", feed, new NycSignMapper(), "New York City", "NY");
        InMemoryRuleRepository repository = new InMemoryRuleRepository();

        GovDataImportCli.run(List.of(source), repository, Clock.fixed(NOW, ZoneOffset.UTC),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void resolveSourcesDefaultsToEverySource() {
        assertThat(GovDataImportCli.resolveSources(new String[0])).hasSize(7);
        assertThat(GovDataImportCli.resolveSources(new String[] {"--all"})).hasSize(7);
    }

    @Test
    void resolveSourcesFiltersByRequestedLabels() {
        List<GovDataImportCli.GovDataSource> selected =
                GovDataImportCli.resolveSources(new String[] {"nyc", "sf"});

        assertThat(selected).extracting(GovDataImportCli.GovDataSource::label).containsExactly("nyc", "sf");
    }

    @Test
    void resolveSourcesRejectsAnUnknownLabel() {
        assertThatThrownBy(() -> GovDataImportCli.resolveSources(new String[] {"atlantis"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("atlantis");
    }

    @Test
    void toExtractionRecordTagsGovDataAndCarriesTheMappedLocation() {
        RuleMetadata metadata = new RuleMetadata("r1", "test rule", SpecificDays.anyDay(), List.of(),
                Optional.empty(), HolidayPolicy.notSuspended(), DirectionalModifier.unspecified());
        MappedRule mapped = new MappedRule(new NoParkingRule(metadata), 40.7, -74.0);

        ExtractionRecord record = GovDataImportCli.toExtractionRecord(
                new GovDataImportCli.GovDataSource("nyc", feedOf(), new NycSignMapper(), "New York City", "NY"),
                mapped, json("{\"a\":1}"), NOW);

        assertThat(record.source()).isEqualTo("gov_data");
        assertThat(record.envelope().source()).isEqualTo("gov_data");
        assertThat(record.envelope().city()).isEqualTo("New York City");
        assertThat(record.extractedAt()).isEqualTo(NOW);
    }
}
