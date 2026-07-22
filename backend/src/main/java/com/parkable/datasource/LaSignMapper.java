package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * LA's verified meter feed supplies a limit and location but no enforcement
 * schedule; the seasonal-location feed supplies geometry but no regulation.
 * Neither can be made into an honest deterministic rule without guessing.
 */
public final class LaSignMapper implements GovRuleMapper {
    public static final String PARSER_VERSION = "gov-la-mapper-v1";
    @Override
    public List<MappedRule> map(JsonNode rawRecord) {
        return List.of();
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }
}
