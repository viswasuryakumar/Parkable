package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Type-specific restriction details (docs/schema.md restriction object). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RestrictionDto(
        @JsonProperty("duration_minutes") Integer durationMinutes,
        @JsonProperty("permit_type") String permitType,
        @JsonProperty("color") String color,
        @JsonProperty("curb_use") String curbUse,
        @JsonProperty("extra_details") String extraDetails
) {}
