package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.parkable.lambda.config.EnvConfig;
import com.parkable.lambda.config.ReportStorageStack;
import com.parkable.repository.RuleReport;
import com.parkable.repository.RuleReportRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * GET /reports — lists "this looks wrong" flags for the app owner to review.
 * Not a real auth system (there are no user accounts anywhere in this app);
 * gated by a single shared secret in the {@code X-Admin-Secret} header,
 * compared against {@code PARKABLE_ADMIN_SECRET} (an SSM param, same pattern
 * as the DB URL/API keys). If the secret isn't configured at all, every
 * request is refused rather than silently left open.
 */
public class ReportsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final RuleReportRepository repository;
    private final Optional<String> adminSecret;

    /** No-arg constructor AWS Lambda actually invokes in production; wires from env vars (D4). */
    public ReportsHandler() {
        this(ReportStorageStack.from(EnvConfig.fromEnvironment()).repository(),
                EnvConfig.fromEnvironment().adminSecret());
    }

    public ReportsHandler(RuleReportRepository repository, Optional<String> adminSecret) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.adminSecret = Objects.requireNonNull(adminSecret, "adminSecret");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        if (adminSecret.isEmpty() || !adminSecret.get().equals(suppliedSecret(event))) {
            return Responses.json(403, Map.of("status", "FORBIDDEN", "message", "Invalid or missing admin secret"));
        }

        List<Map<String, Object>> reports = repository.list().stream()
                .map(ReportsHandler::summarize)
                .toList();
        return Responses.json(200, Map.of("reports", reports));
    }

    private static String suppliedSecret(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = event.getHeaders();
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("X-Admin-Secret"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> summarize(RuleReport report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", report.id().toString());
        summary.put("rule_id", report.ruleId());
        summary.put("reason", report.reason());
        summary.put("device_id", report.deviceId());
        summary.put("reported_at", report.reportedAt().toString());
        return summary;
    }
}
