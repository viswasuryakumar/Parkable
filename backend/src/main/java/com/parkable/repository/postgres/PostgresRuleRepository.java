package com.parkable.repository.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkable.extraction.ClaudeVisionExtractor;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.factory.RuleFactory;
import com.parkable.lambda.port.RuleLookup;
import com.parkable.lambda.port.StoredRule;
import com.parkable.repository.ExtractionRecord;
import com.parkable.repository.RuleRepository;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostGIS-backed storage for extracted rules. Each row holds one rule so the
 * GiST index can answer a geographic lookup without loading whole scans.
 */
public final class PostgresRuleRepository implements RuleRepository, RuleLookup {

    static final String UPSERT_RULE = """
            INSERT INTO rules (id, location, rule, source, parser_version, created_at)
            VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?::jsonb, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                location = EXCLUDED.location,
                rule = EXCLUDED.rule,
                source = EXCLUDED.source,
                parser_version = EXCLUDED.parser_version,
                created_at = EXCLUDED.created_at
            """;

    static final String FIND_WITHIN = """
            SELECT rule, source, parser_version
            FROM rules
            WHERE parser_version = ?
              AND ST_DWithin(
                  location,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?
              )
            ORDER BY ST_Distance(
                location,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            )
            """;

    private static final String FIND_BY_EXTRACTION_ID = """
            SELECT rule, source, parser_version,
                   ST_Y(location::geometry) AS latitude,
                   ST_X(location::geometry) AS longitude
            FROM rules
            WHERE rule #>> '{_parkable,extraction_id}' = ?
            ORDER BY created_at, id
            """;

    private static final String FIND_ALL = """
            SELECT rule, source, parser_version,
                   ST_Y(location::geometry) AS latitude,
                   ST_X(location::geometry) AS longitude
            FROM rules
            ORDER BY created_at, id
            """;

    private static final String PARKABLE_METADATA = "_parkable";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;

    /**
     * Required by the Lambda composition root (Phase 2 decision D6).
     * Credentials and pooling options belong in the JDBC URL supplied through
     * {@code PARKABLE_DB_URL}; keeping this constructor to one value makes the
     * deployment wiring explicit and reflective loading stable.
     */
    public PostgresRuleRepository(String jdbcUrl) {
        this.jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
    }

    @Override
    public void save(ExtractionRecord record) {
        Objects.requireNonNull(record, "record");
        ExtractionRecord.GpsCoordinates coordinates = record.gpsLocation()
                .orElseThrow(() -> new IllegalArgumentException("Postgres storage requires gpsLocation"));

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_RULE)) {
            for (RuleDto rule : record.envelope().rules()) {
                statement.setObject(1, stableRuleId(record.extractionId(), rule.ruleId()));
                // PostGIS points take x then y: longitude before latitude.
                statement.setDouble(2, coordinates.longitude());
                statement.setDouble(3, coordinates.latitude());
                statement.setString(4, encodeRule(record, rule));
                statement.setString(5, record.source());
                statement.setString(6, record.parserVersion());
                statement.setObject(7, record.extractedAt());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw storageFailure("save rules", e);
        }
    }

    @Override
    public Optional<ExtractionRecord> findByExtractionId(String extractionId) {
        requireNonBlank(extractionId, "extractionId");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_EXTRACTION_ID)) {
            statement.setString(1, extractionId);
            try (ResultSet results = statement.executeQuery()) {
                List<PersistedRule> rules = readPersistedRules(results);
                return rules.isEmpty() ? Optional.empty() : Optional.of(toExtractionRecord(rules));
            }
        } catch (SQLException e) {
            throw storageFailure("find extraction", e);
        }
    }

    @Override
    public List<ExtractionRecord> findAll() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_ALL);
             ResultSet results = statement.executeQuery()) {
            Map<String, List<PersistedRule>> byExtractionId = new LinkedHashMap<>();
            for (PersistedRule rule : readPersistedRules(results)) {
                byExtractionId.computeIfAbsent(rule.extractionId(), ignored -> new ArrayList<>()).add(rule);
            }
            return byExtractionId.values().stream().map(PostgresRuleRepository::toExtractionRecord).toList();
        } catch (SQLException e) {
            throw storageFailure("list extractions", e);
        }
    }

    @Override
    public List<StoredRule> findWithin(double latitude, double longitude, double radiusMeters) {
        validateCoordinates(latitude, longitude);
        if (radiusMeters < 0) {
            throw new IllegalArgumentException("radiusMeters must not be negative");
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_WITHIN)) {
            statement.setString(1, currentParserVersion());
            // Every PostGIS point binding is longitude then latitude.
            statement.setDouble(2, longitude);
            statement.setDouble(3, latitude);
            statement.setDouble(4, radiusMeters);
            statement.setDouble(5, longitude);
            statement.setDouble(6, latitude);
            try (ResultSet results = statement.executeQuery()) {
                List<StoredRule> stored = new ArrayList<>();
                while (results.next()) {
                    stored.add(toStoredRule(
                            results.getString("rule"),
                            results.getString("source"),
                            results.getString("parser_version")));
                }
                return List.copyOf(stored);
            }
        } catch (SQLException e) {
            throw storageFailure("find nearby rules", e);
        }
    }

    static UUID stableRuleId(String extractionId, String ruleId) {
        return UUID.nameUUIDFromBytes((requireNonBlank(extractionId, "extractionId") + "\\n"
                + requireNonBlank(ruleId, "ruleId")).getBytes(StandardCharsets.UTF_8));
    }

    static String encodeRule(ExtractionRecord record, RuleDto rule) {
        try {
            ObjectNode storedRule = MAPPER.valueToTree(rule);
            ObjectNode metadata = storedRule.putObject(PARKABLE_METADATA);
            metadata.put("extraction_id", record.extractionId());
            metadata.put("photo_reference", record.photoReference());
            metadata.put("extracted_at", record.extractedAt().toString());

            ObjectNode envelope = MAPPER.valueToTree(record.envelope());
            envelope.remove("rules");
            metadata.set("envelope", envelope);
            return MAPPER.writeValueAsString(storedRule);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize stored rule", e);
        }
    }

    static StoredRule toStoredRule(String json, String source, String parserVersion) {
        try {
            RuleDto dto = MAPPER.readValue(json, RuleDto.class);
            return new StoredRule(RuleFactory.from(dto), source, parserVersion);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored rule is not valid JSON", e);
        }
    }

    private Connection openConnection() throws SQLException {
        try {
            return DriverManager.getConnection(jdbcUrl);
        } catch (SQLException e) {
            // DriverManager bakes the full connection string - credentials
            // included - into its own exception message, and that message
            // otherwise propagates verbatim into Lambda's CloudWatch logs.
            // Deliberately do NOT chain the original exception as a cause:
            // any stack-trace printer would still render its message.
            throw new SQLException("Failed to connect to Postgres (see " + redactedUrl() + ")", e.getSQLState());
        }
    }

    private String redactedUrl() {
        return jdbcUrl.replaceAll("://[^@/]+@", "://<redacted>@");
    }

    private static List<PersistedRule> readPersistedRules(ResultSet results) throws SQLException {
        List<PersistedRule> rules = new ArrayList<>();
        while (results.next()) {
            try {
                JsonNode stored = MAPPER.readTree(results.getString("rule"));
                JsonNode metadata = stored.path(PARKABLE_METADATA);
                if (metadata.isMissingNode()) {
                    throw new IllegalStateException("Stored rule is missing _parkable extraction metadata");
                }
                rules.add(new PersistedRule(
                        MAPPER.treeToValue(stored, RuleDto.class),
                        metadata,
                        results.getString("source"),
                        results.getString("parser_version"),
                        results.getDouble("latitude"),
                        results.getDouble("longitude")));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored rule is not valid JSON", e);
            }
        }
        return rules;
    }

    private static ExtractionRecord toExtractionRecord(List<PersistedRule> persistedRules) {
        PersistedRule first = persistedRules.getFirst();
        try {
            ObjectNode envelope = (ObjectNode) first.metadata().path("envelope").deepCopy();
            envelope.set("rules", MAPPER.valueToTree(persistedRules.stream().map(PersistedRule::dto).toList()));
            ExtractionEnvelope parsedEnvelope = MAPPER.treeToValue(envelope, ExtractionEnvelope.class);
            return new ExtractionRecord(
                    parsedEnvelope,
                    first.metadata().path("photo_reference").asText(),
                    first.source(),
                    first.parserVersion(),
                    Optional.of(new ExtractionRecord.GpsCoordinates(first.latitude(), first.longitude())),
                    Instant.parse(first.metadata().path("extracted_at").asText()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored extraction metadata is not valid JSON", e);
        }
    }

    private static String currentParserVersion() {
        String configured = System.getenv("PARKABLE_PARSER_VERSION");
        return configured == null || configured.isBlank() ? ClaudeVisionExtractor.PARSER_VERSION : configured;
    }

    private static void validateCoordinates(double latitude, double longitude) {
        new ExtractionRecord.GpsCoordinates(latitude, longitude);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException storageFailure(String operation, SQLException cause) {
        return new IllegalStateException("Postgres failed to " + operation, cause);
    }

    private record PersistedRule(
            RuleDto dto,
            JsonNode metadata,
            String source,
            String parserVersion,
            double latitude,
            double longitude) {

        String extractionId() {
            return metadata.path("extraction_id").asText();
        }
    }
}
