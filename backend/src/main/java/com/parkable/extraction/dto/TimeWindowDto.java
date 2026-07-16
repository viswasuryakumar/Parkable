package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A daily enforcement window in HH:mm 24h form (docs/schema.md time_windows[]). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimeWindowDto(
        @JsonProperty("start_time") String startTime,
        @JsonProperty("end_time") String endTime,
        @JsonProperty("crosses_midnight") Boolean crossesMidnight,
        @JsonProperty("all_day") Boolean allDay
) {}
