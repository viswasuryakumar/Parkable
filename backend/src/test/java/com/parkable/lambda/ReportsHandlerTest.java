package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.repository.InMemoryRuleReportRepository;
import com.parkable.repository.RuleReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportsHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode body(APIGatewayProxyResponseEvent response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static APIGatewayProxyRequestEvent withSecretHeader(String value) {
        return new APIGatewayProxyRequestEvent().withHeaders(Map.of("X-Admin-Secret", value));
    }

    @Test
    void rejectsRequestsWithoutTheCorrectSecret() {
        ReportsHandler handler = new ReportsHandler(new InMemoryRuleReportRepository(), Optional.of("correct-secret"));

        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent(), null).getStatusCode()).isEqualTo(403);
        assertThat(handler.handleRequest(withSecretHeader("wrong"), null).getStatusCode()).isEqualTo(403);
    }

    @Test
    void refusesEveryRequestWhenNoSecretIsConfigured() {
        ReportsHandler handler = new ReportsHandler(new InMemoryRuleReportRepository(), Optional.empty());

        APIGatewayProxyResponseEvent response = handler.handleRequest(withSecretHeader("anything"), null);

        assertThat(response.getStatusCode()).isEqualTo(403);
    }

    @Test
    void listsReportsMostRecentFirstWhenTheSecretMatches() {
        InMemoryRuleReportRepository repository = new InMemoryRuleReportRepository();
        repository.save(new RuleReport(UUID.randomUUID(), "rule-1", "sign is gone", "device-1",
                Instant.parse("2026-07-01T00:00:00Z")));
        repository.save(new RuleReport(UUID.randomUUID(), "rule-2", "wrong hours", "device-2",
                Instant.parse("2026-07-02T00:00:00Z")));
        ReportsHandler handler = new ReportsHandler(repository, Optional.of("correct-secret"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(withSecretHeader("correct-secret"), null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        JsonNode reports = body(response).get("reports");
        assertThat(reports).hasSize(2);
        assertThat(reports.get(0).get("rule_id").asText()).isEqualTo("rule-2");
        assertThat(reports.get(0).get("reason").asText()).isEqualTo("wrong hours");
        assertThat(reports.get(1).get("rule_id").asText()).isEqualTo("rule-1");
    }
}
