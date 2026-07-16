package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Exception / special case attached to a rule (docs/schema.md exceptions[]). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExceptionDto(
        @JsonProperty("exception_type") String exceptionType,
        @JsonProperty("description") String description,
        @JsonProperty("applies_to") List<String> appliesTo,
        @JsonProperty("exception_rule") String exceptionRule,
        @JsonProperty("date_override") String dateOverride
) {}
