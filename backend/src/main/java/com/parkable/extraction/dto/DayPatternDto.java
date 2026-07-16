package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Day-based applicability (docs/schema.md day_pattern object). Which fields
 * are meaningful depends on {@code type}; the JSON schema's conditional
 * requirements enforce the combinations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DayPatternDto(
        @JsonProperty("type") String type,
        @JsonProperty("days_of_week") List<String> daysOfWeek,
        @JsonProperty("weekday") String weekday,
        @JsonProperty("occurrences") List<Integer> occurrences,
        @JsonProperty("effective_date") String effectiveDate,
        @JsonProperty("sunset_date") String sunsetDate,
        @JsonProperty("year_round") Boolean yearRound
) {}
