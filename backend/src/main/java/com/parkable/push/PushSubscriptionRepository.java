package com.parkable.push;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for browser push registrations. An interface for the same reason
 * every other data source in this project has one: the notify path is
 * testable without a database, and a future non-Postgres store is a new class
 * rather than a rewrite.
 */
public interface PushSubscriptionRepository {

    /**
     * Stores a subscription, or refreshes the keys of the one already holding
     * this endpoint. Browsers reissue the same endpoint on every resubscribe,
     * so upserting keeps one row per browser instead of one per visit.
     */
    PushSubscription upsert(URI endpoint, String p256dh, String auth);

    Optional<PushSubscription> findById(UUID id);

    /**
     * Drops a subscription the push service has reported as gone (HTTP 404 or
     * 410). Keeping it would mean retrying a dead endpoint forever.
     */
    void delete(UUID id);
}
