package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.Rule;

import java.util.List;

/**
 * LA's verified meter feed supplies a limit and location but no enforcement
 * schedule; the seasonal-location feed supplies geometry but no regulation.
 * Neither can be made into an honest deterministic rule without guessing.
 */
public final class LaSignMapper implements GovRuleMapper {
    @Override
    public List<Rule> map(JsonNode rawRecord) {
        return List.of();
    }

    @Override
    public String parserVersion() {
        return "gov-la-mapper-v1";
    }
}
