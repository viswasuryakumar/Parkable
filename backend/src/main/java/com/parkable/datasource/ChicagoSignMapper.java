package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.Rule;

import java.util.List;

/**
 * Chicago's verified permit-zone feed identifies a zone and address range but
 * publishes no days or hours of enforcement. It is therefore provenance, not
 * an enforceable rule, until the city exposes its ordinance schedule.
 */
public final class ChicagoSignMapper implements GovRuleMapper {
    @Override
    public List<Rule> map(JsonNode rawRecord) {
        return List.of();
    }

    @Override
    public String parserVersion() {
        return "gov-chicago-mapper-v1";
    }
}
