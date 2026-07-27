package com.parkable.lambda.config;

import com.parkable.factory.RuleFactory;
import com.parkable.lambda.port.RuleLookup;
import com.parkable.lambda.port.StoredRule;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.InMemoryRuleRepository;

import java.util.List;
import java.util.Objects;

/**
 * Dev/local {@link RuleLookup} over the in-memory repository: linear scan +
 * haversine distance. Fine for a handful of records in tests and offline
 * development; real traffic goes through PostgresRuleRepository's indexed
 * ST_DWithin instead (plan decision D4 — this is the fallback, not the target).
 */
final class InMemoryRuleLookup implements RuleLookup {

    private final InMemoryRuleRepository repository;

    InMemoryRuleLookup(InMemoryRuleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<StoredRule> findWithin(double latitude, double longitude, double radiusMeters) {
        return repository.findAll().stream()
                .filter(record -> record.gpsLocation().isPresent())
                .filter(record -> withinRadius(record, latitude, longitude, radiusMeters))
                .flatMap(record -> RuleFactory.fromEnvelope(record.envelope()).stream()
                        .map(rule -> new StoredRule(rule, record.source(), record.parserVersion(),
                                record.gpsLocation().orElseThrow().latitude(),
                                record.gpsLocation().orElseThrow().longitude(),
                                record.extractionId(), record.photoReference())))
                .toList();
    }

    private static boolean withinRadius(ExtractionRecord record, double lat, double lng, double radiusMeters) {
        var gps = record.gpsLocation().orElseThrow();
        return GeoDistance.metersBetween(gps.latitude(), gps.longitude(), lat, lng) <= radiusMeters;
    }
}
