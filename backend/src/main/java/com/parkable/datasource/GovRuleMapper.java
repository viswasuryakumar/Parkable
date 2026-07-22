package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** City-specific, deterministic normalization of one government dataset record. */
public interface GovRuleMapper {
    List<MappedRule> map(JsonNode rawRecord);

    String parserVersion();
}
