package com.parkable.extraction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Side-of-street / arrow qualifiers (docs/schema.md direction object). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DirectionDto(
        @JsonProperty("side_of_street") String sideOfStreet,
        @JsonProperty("cardinal") String cardinal,
        @JsonProperty("house_numbers") String houseNumbers,
        @JsonProperty("arrow") String arrow
) {}
