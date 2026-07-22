package com.parkable.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.datasource.ArcGisGovDataFeed;
import com.parkable.datasource.ChicagoSignMapper;
import com.parkable.datasource.GovDataFeed;
import com.parkable.datasource.GovRuleMapper;
import com.parkable.datasource.LaSignMapper;
import com.parkable.datasource.MappedRule;
import com.parkable.datasource.NycSignMapper;
import com.parkable.datasource.SeattleSignMapper;
import com.parkable.datasource.SfSignMapper;
import com.parkable.datasource.SocrataGovDataFeed;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.factory.RuleFactory;
import com.parkable.lambda.config.EnvConfig;
import com.parkable.lambda.config.StorageStack;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.RuleRepository;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Phase 2.5 batch ETL: pulls every configured government dataset, maps each
 * record through its city's {@link GovRuleMapper}, and persists confidently-
 * mapped rules through the same {@link RuleRepository} camera scans use —
 * {@code source="gov_data"} plus the mapper's own parser_version is all
 * {@code /check}/{@code /nearby} need to serve it (no handler changes).
 *
 * <p>Thin orchestration only, like {@link ScanCLI}: all rule-content and
 * location decisions already happened inside the mapper (Codex,
 * {@code com.parkable.datasource}); this class just wires feed to mapper to
 * repository and reports counts.
 */
public final class GovDataImportCli {

    /** One dataset to pull; a "city" label may cover more than one source (LA, Seattle). */
    record GovDataSource(String label, GovDataFeed feed, GovRuleMapper mapper, String city, String state) {}

    private GovDataImportCli() {}

    private static List<GovDataSource> allSources() {
        return List.of(
                new GovDataSource("nyc",
                        new SocrataGovDataFeed("data.cityofnewyork.us", "afgb-4qw7"),
                        new NycSignMapper(), "New York City", "NY"),
                new GovDataSource("chicago",
                        new SocrataGovDataFeed("data.cityofchicago.org", "u9xt-hiju"),
                        new ChicagoSignMapper(), "Chicago", "IL"),
                new GovDataSource("la-metered",
                        new SocrataGovDataFeed("data.lacity.org", "s49e-q6j2"),
                        new LaSignMapper(), "Los Angeles", "CA"),
                new GovDataSource("la-seasonal",
                        new SocrataGovDataFeed("data.lacity.org", "jp2s-nfz4"),
                        new LaSignMapper(), "Los Angeles", "CA"),
                new GovDataSource("sf",
                        new ArcGisGovDataFeed(
                                "https://services.sfmta.com/arcgis/rest/services/DataSF/master/FeatureServer/24/query"),
                        new SfSignMapper(), "San Francisco", "CA"),
                new GovDataSource("seattle-street-signs",
                        new ArcGisGovDataFeed("https://services.arcgis.com/ZOyb2t4B0UYuYNYH/arcgis/rest/services/"
                                + "SDOT_Street_Signs/FeatureServer/1/query"),
                        new SeattleSignMapper(), "Seattle", "WA"),
                new GovDataSource("seattle-peak-hour",
                        new ArcGisGovDataFeed("https://services.arcgis.com/ZOyb2t4B0UYuYNYH/arcgis/rest/services/"
                                + "Peak_Hour_Parking_Restrictions/FeatureServer/3/query"),
                        new SeattleSignMapper(), "Seattle", "WA"));
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC(), System.out, System.err));
    }

    static int run(String[] args, Clock clock, PrintStream out, PrintStream err) {
        List<GovDataSource> selected;
        try {
            selected = resolveSources(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println("Known sources: " + allSources().stream().map(GovDataSource::label)
                    .collect(Collectors.joining(", ")));
            err.println("Usage: GovDataImportCli [--all | <source> ...]");
            return 64;
        }
        RuleRepository repository = StorageStack.from(EnvConfig.fromEnvironment()).repository();
        return run(selected, repository, clock, out);
    }

    /** Testable without touching the network/environment — the actual import loop. */
    static int run(List<GovDataSource> selected, RuleRepository repository, Clock clock, PrintStream out) {
        for (GovDataSource source : selected) {
            importSource(source, repository, clock, out);
        }
        return 0;
    }

    static List<GovDataSource> resolveSources(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--all".equals(args[0]))) {
            return allSources();
        }
        List<GovDataSource> selected = new ArrayList<>();
        for (String label : args) {
            selected.add(allSources().stream()
                    .filter(source -> source.label().equals(label))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + label)));
        }
        return selected;
    }

    private static void importSource(GovDataSource source, RuleRepository repository, Clock clock, PrintStream out) {
        int recordsSeen = 0;
        int recordsDropped = 0;
        int rulesImported = 0;
        Iterator<JsonNode> records = source.feed().fetchAll();
        while (records.hasNext()) {
            JsonNode raw = records.next();
            recordsSeen++;
            List<MappedRule> mapped = source.mapper().map(raw);
            if (mapped.isEmpty()) {
                recordsDropped++;
                continue;
            }
            for (MappedRule rule : mapped) {
                repository.save(toExtractionRecord(source, rule, raw, clock.instant()));
                rulesImported++;
            }
        }
        out.printf("%s: %d records seen, %d rules imported, %d records dropped (no confident mapping)%n",
                source.label(), recordsSeen, rulesImported, recordsDropped);
    }

    static ExtractionRecord toExtractionRecord(GovDataSource source, MappedRule mapped, JsonNode rawRecord, Instant now) {
        RuleDto dto = RuleFactory.toDto(mapped.rule());
        // The mapper's ruleId is already source-qualified and stable (e.g.
        // "nyc:afgb-4qw7:S-42"), so reusing it as the extraction id too makes
        // a re-run resolve to the same stableRuleId (G4: idempotent re-import).
        String extractionId = dto.ruleId();
        ExtractionEnvelope envelope = new ExtractionEnvelope(
                extractionId, "gov_data", source.city(), source.state(), source.mapper().parserVersion(),
                now.toString(), "gov_data_etl", 1.0, null, null, rawRecord.toString(), List.of(dto));
        return new ExtractionRecord(
                envelope, extractionId, "gov_data", source.mapper().parserVersion(),
                Optional.of(new ExtractionRecord.GpsCoordinates(mapped.latitude(), mapped.longitude())), now);
    }
}
