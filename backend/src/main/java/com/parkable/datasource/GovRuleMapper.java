package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.Rule;

import java.util.List;

/** City-specific, deterministic normalization of one government dataset record. */
public interface GovRuleMapper {
    List<Rule> map(JsonNode rawRecord);

    String parserVersion();
}
