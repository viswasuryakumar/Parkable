package com.parkable.repository.postgres;

import com.parkable.push.PushSubscription;
import com.parkable.push.PushSubscriptionRepository;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Postgres storage for browser push registrations. Same shape as
 * {@link PostgresRuleReportRepository} - reuses that package's libpq-style
 * credential parsing rather than re-deriving it.
 */
public final class PostgresPushSubscriptionRepository implements PushSubscriptionRepository {

    /**
     * Upsert on endpoint, not id: the browser reissues the same endpoint every
     * time it resubscribes, and its keys can change underneath it. Matching on
     * endpoint refreshes that row in place, so a browser never accumulates
     * duplicate registrations - and RETURNING hands back the existing id when
     * the row was already there.
     */
    private static final String UPSERT = """
            INSERT INTO push_subscriptions (id, endpoint, p256dh, auth)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (endpoint) DO UPDATE
                SET p256dh = EXCLUDED.p256dh,
                    auth = EXCLUDED.auth,
                    last_seen_at = CURRENT_TIMESTAMP
            RETURNING id, endpoint, p256dh, auth
            """;

    private static final String SELECT_BY_ID =
            "SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE id = ?";

    private static final String DELETE_BY_ID = "DELETE FROM push_subscriptions WHERE id = ?";

    private final String jdbcUrl;

    public PostgresPushSubscriptionRepository(String jdbcUrl) {
        this.jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
    }

    @Override
    public PushSubscription upsert(URI endpoint, String p256dh, String auth) {
        Objects.requireNonNull(endpoint, "endpoint");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, endpoint.toString());
            statement.setString(3, p256dh);
            statement.setString(4, auth);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Upsert returned no push subscription row");
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres failed to save push subscription", e);
        }
    }

    @Override
    public Optional<PushSubscription> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres failed to load push subscription", e);
        }
    }

    @Override
    public void delete(UUID id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_ID)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres failed to delete push subscription", e);
        }
    }

    private static PushSubscription read(ResultSet rs) throws SQLException {
        return new PushSubscription(
                UUID.fromString(rs.getString("id")),
                URI.create(rs.getString("endpoint")),
                rs.getString("p256dh"),
                rs.getString("auth"));
    }

    private Connection openConnection() throws SQLException {
        PostgresRuleRepository.ParsedConnection parsed = PostgresRuleRepository.parse(jdbcUrl);
        Properties properties = new Properties();
        properties.setProperty("prepareThreshold", "0");
        if (parsed.user() != null) {
            properties.setProperty("user", parsed.user());
            properties.setProperty("password", parsed.password());
        }
        return DriverManager.getConnection(parsed.url(), properties);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
