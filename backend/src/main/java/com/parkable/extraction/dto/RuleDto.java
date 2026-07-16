package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One regulation from the extraction envelope (docs/schema.md Rule Object).
 * Enum-like fields stay Strings here; schema validation guarantees their
 * values before RuleFactory maps them to domain enums.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuleDto(
        @JsonProperty("rule_id") String ruleId,
        @JsonProperty("source_rule_id") String sourceRuleId,
        @JsonProperty("type") String type,
        @JsonProperty("description") String description,
        @JsonProperty("original_description") String originalDescription,
        @JsonProperty("restriction") RestrictionDto restriction,
        @JsonProperty("time_windows") List<TimeWindowDto> timeWindows,
        @JsonProperty("day_pattern") DayPatternDto dayPattern,
        @JsonProperty("exceptions") List<ExceptionDto> exceptions,
        @JsonProperty("direction") DirectionDto direction,
        @JsonProperty("status") String status
) {}
