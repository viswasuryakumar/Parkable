package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.parkable.push.PushSubscription;
import com.parkable.push.PushSubscriptionRepository;
import com.parkable.push.ReminderScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushRemindersHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123";

    private FakeRepository repository;
    private ReminderScheduler scheduler;
    private PushRemindersHandler handler;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        scheduler = mock(ReminderScheduler.class);
        handler = new PushRemindersHandler(repository, scheduler, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("schedules reminders for a deadline and reports how many were set")
    void schedulesReminders() {
        when(scheduler.schedule(any(), any(), any())).thenReturn(2);

        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("""
                        {"endpoint":"%s","p256dh":"key","auth":"secret","valid_until":"2026-08-12T11:00:00Z"}
                        """.formatted(ENDPOINT)), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"SCHEDULED\"").contains("\"reminders\":2");
        verify(scheduler).schedule(any(UUID.class), eq(Instant.parse("2026-08-12T11:00:00Z")), eq(NOW));
    }

    @Test
    @DisplayName("a null valid_until means the car moved, so reminders are cancelled")
    void cancelsWhenNoDeadline() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("""
                        {"endpoint":"%s","p256dh":"key","auth":"secret"}
                        """.formatted(ENDPOINT)), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"CANCELLED\"");
        verify(scheduler).cancel(any(UUID.class));
        verify(scheduler, never()).schedule(any(), any(), any());
    }

    @Test
    @DisplayName("rejects an endpoint that is not absolute https")
    void rejectsNonHttpsEndpoint() {
        // Otherwise a caller could store an arbitrary URL and have our
        // VAPID-signed requests delivered to it later.
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("""
                        {"endpoint":"http://attacker.invalid/collect","p256dh":"key","auth":"secret"}
                        """), null);

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("absolute https URL");
        assertThat(repository.saved).isEmpty();
    }

    @Test
    @DisplayName("rejects a request missing subscription keys")
    void rejectsMissingKeys() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("""
                        {"endpoint":"%s"}""".formatted(ENDPOINT)), null);

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("endpoint, p256dh, and auth");
    }

    @Test
    @DisplayName("rejects a malformed valid_until instead of silently not scheduling")
    void rejectsUnparseableDeadline() {
        APIGatewayProxyResponseEvent response = handler.handleRequest(
                request("""
                        {"endpoint":"%s","p256dh":"key","auth":"secret","valid_until":"6pm tomorrow"}
                        """.formatted(ENDPOINT)), null);

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("ISO-8601");
        verify(scheduler, never()).schedule(any(), any(), any());
    }

    @Test
    @DisplayName("rejects a body that is not JSON")
    void rejectsNonJsonBody() {
        assertThat(handler.handleRequest(request("not json"), null).getStatusCode()).isEqualTo(400);
    }

    private static APIGatewayProxyRequestEvent request(String body) {
        return new APIGatewayProxyRequestEvent().withBody(body);
    }

    /** Minimal stand-in; the Postgres upsert semantics are exercised separately. */
    private static final class FakeRepository implements PushSubscriptionRepository {
        private final Map<URI, PushSubscription> saved = new HashMap<>();

        @Override
        public PushSubscription upsert(URI endpoint, String p256dh, String auth) {
            return saved.computeIfAbsent(endpoint,
                    key -> new PushSubscription(UUID.randomUUID(), key, p256dh, auth));
        }

        @Override
        public Optional<PushSubscription> findById(UUID id) {
            return saved.values().stream().filter(s -> s.id().equals(id)).findFirst();
        }

        @Override
        public void delete(UUID id) {
            saved.values().removeIf(s -> s.id().equals(id));
        }
    }
}
