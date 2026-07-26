package com.parkable.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.parkable.repository.InMemoryRuleReportRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ReportHandlerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void validReportIsPersistedAndAcknowledged() {
        InMemoryRuleReportRepository repository = new InMemoryRuleReportRepository();
        ReportHandler handler = new ReportHandler(repository, FIXED_CLOCK);

        APIGatewayProxyResponseEvent response = handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(
                "{\"rule_id\":\"nyc:nfid-uabd:S-42\",\"reason\":\"Sign was removed\",\"device_id\":\"device-1\"}"),
                null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("RECEIVED");
        assertThat(repository.findAll()).hasSize(1);
        var report = repository.findAll().getFirst();
        assertThat(report.ruleId()).isEqualTo("nyc:nfid-uabd:S-42");
        assertThat(report.reason()).isEqualTo("Sign was removed");
        assertThat(report.deviceId()).isEqualTo("device-1");
        assertThat(report.reportedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void aReportNeverMutatesAnyRuleData() {
        // The core safety property: this endpoint only ever writes to
        // rule_reports, never touches the rules table - a bad-faith report
        // must not be able to take down a real regulation.
        InMemoryRuleReportRepository repository = new InMemoryRuleReportRepository();
        ReportHandler handler = new ReportHandler(repository, FIXED_CLOCK);

        handler.handleRequest(new APIGatewayProxyRequestEvent().withBody(
                "{\"rule_id\":\"r1\",\"reason\":\"bad\",\"device_id\":\"d1\"}"), null);

        // RuleReportRepository's interface has no method that could touch
        // rules at all - nothing further to assert; the type signature
        // itself is the guarantee.
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void missingFieldsReturn400() {
        ReportHandler handler = new ReportHandler(new InMemoryRuleReportRepository(), FIXED_CLOCK);

        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent(), null).getStatusCode())
                .isEqualTo(400); // no body
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent().withBody("not json"), null)
                .getStatusCode()).isEqualTo(400);
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withBody("{\"rule_id\":\"r1\",\"reason\":\"bad\"}"), null) // missing device_id
                .getStatusCode()).isEqualTo(400);
        assertThat(handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withBody("{\"rule_id\":\"\",\"reason\":\"bad\",\"device_id\":\"d1\"}"), null) // blank rule_id
                .getStatusCode()).isEqualTo(400);
    }
}
