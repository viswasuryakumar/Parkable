package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.lambda.config.PushStack;
import com.parkable.push.PushSubscription;
import com.parkable.push.PushSubscriptionRepository;
import com.parkable.push.ReminderScheduler;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * POST /push/reminders — register this browser for parking reminders and set
 * (or clear) the reminders for the current session.
 *
 * One endpoint rather than separate subscribe/schedule/cancel calls because
 * that matches what the client actually does: starting a timer is a single
 * user action, and it should be a single request that either succeeds or
 * fails. A null {@code valid_until} means "I've moved my car" and cancels.
 */
public class PushRemindersHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PushSubscriptionRepository repository;
    private final ReminderScheduler scheduler;
    private final Clock clock;

    /** No-arg constructor Lambda invokes in production. */
    public PushRemindersHandler() {
        PushStack stack = PushStack.fromEnvironment();
        this.repository = stack.repository();
        this.scheduler = stack.scheduler();
        this.clock = Clock.systemUTC();
    }

    public PushRemindersHandler(PushSubscriptionRepository repository, ReminderScheduler scheduler, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String body = event.getBody();
            if (body == null || body.isBlank()) {
                return Responses.badRequest("Request body is required");
            }
            JsonNode json;
            try {
                json = mapper.readTree(body);
            } catch (JacksonException e) {
                return Responses.badRequest("Request body is not valid JSON");
            }

            String endpoint = text(json, "endpoint");
            String p256dh = text(json, "p256dh");
            String auth = text(json, "auth");
            if (endpoint == null || p256dh == null || auth == null) {
                return Responses.badRequest("endpoint, p256dh, and auth are all required");
            }

            URI endpointUri;
            try {
                endpointUri = URI.create(endpoint);
            } catch (IllegalArgumentException e) {
                return Responses.badRequest("endpoint is not a valid URI");
            }
            // A push endpoint is always absolute https. Rejecting anything else
            // keeps an attacker from parking an arbitrary URL in the table and
            // having our credentials POST to it later.
            if (!"https".equalsIgnoreCase(endpointUri.getScheme()) || endpointUri.getHost() == null) {
                return Responses.badRequest("endpoint must be an absolute https URL");
            }

            PushSubscription subscription = repository.upsert(endpointUri, p256dh, auth);

            String validUntil = text(json, "valid_until");
            if (validUntil == null) {
                scheduler.cancel(subscription.id());
                return Responses.json(200, Map.of(
                        "status", "CANCELLED",
                        "subscription_id", subscription.id().toString()));
            }

            Instant deadline;
            try {
                deadline = Instant.parse(validUntil);
            } catch (DateTimeParseException e) {
                return Responses.badRequest("valid_until must be an ISO-8601 instant");
            }

            int scheduled = scheduler.schedule(subscription.id(), deadline, clock.instant());
            return Responses.json(200, Map.of(
                    "status", scheduled > 0 ? "SCHEDULED" : "NOT_SCHEDULED",
                    "reminders", scheduled,
                    "subscription_id", subscription.id().toString()));
        } catch (RuntimeException e) {
            return Responses.badRequest("Could not set parking reminders");
        }
    }

    private static String text(JsonNode json, String name) {
        JsonNode node = json.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
