package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.lambda.config.EnvConfig;
import com.parkable.lambda.config.ReportStorageStack;
import com.parkable.repository.RuleReport;
import com.parkable.repository.RuleReportRepository;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * POST /report — "this rule looks wrong/outdated." Persists only, for later
 * human review; never automatically changes or removes the rule it points
 * at (a bad-faith report must not be able to take down a real regulation).
 */
public class ReportHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleReportRepository repository;
    private final Clock clock;

    /** No-arg constructor AWS Lambda actually invokes in production; wires from env vars (D4). */
    public ReportHandler() {
        this(ReportStorageStack.from(EnvConfig.fromEnvironment()).repository(), Clock.systemUTC());
    }

    public ReportHandler(RuleReportRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
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

            String ruleId = textField(json, "rule_id");
            String reason = textField(json, "reason");
            String deviceId = textField(json, "device_id");
            if (ruleId == null || reason == null || deviceId == null) {
                return Responses.badRequest("rule_id, reason, and device_id are all required");
            }

            repository.save(new RuleReport(UUID.randomUUID(), ruleId, reason, deviceId, clock.instant()));
            return Responses.json(200, Map.of("status", "RECEIVED"));
        } catch (RuntimeException e) {
            return Responses.badRequest("Could not process report");
        }
    }

    private static String textField(JsonNode json, String name) {
        JsonNode node = json.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
