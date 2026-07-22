package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Maps Seattle's sign inventory when its structured day/time fields establish
 * a no-parking restriction. Peak-hour segment records are deliberately
 * skipped: their published PKHRDESC lacks the days of enforcement.
 */
public final class SeattleSignMapper implements GovRuleMapper {
    public static final String PARSER_VERSION = "gov-seattle-mapper-v1";
    @Override
    public List<MappedRule> map(JsonNode rawRecord) {
        Optional<String> unitId = GovMapperSupport.text(rawRecord, "UNITID");
        Optional<String> category = GovMapperSupport.text(rawRecord, "CATEGORY");
        Optional<String> status = GovMapperSupport.text(rawRecord, "CURRENT_STATUS");
        if (unitId.isEmpty() || category.isEmpty() || status.isEmpty()
                || !"PNP".equals(category.get()) || !"INSVC".equals(status.get())) {
            return List.of();
        }
        JsonNode attributes = GovMapperSupport.attributes(rawRecord);
        if (!"1".equals(attributes.path("STARTDAY").asText()) || !"7".equals(attributes.path("ENDDAY").asText())) {
            return List.of();
        }
        Optional<com.parkable.model.TimeWindow> window = GovMapperSupport.militaryWindow(rawRecord, "STARTTIME", "ENDTIME");
        Optional<GovMapperSupport.Coordinates> location = GovMapperSupport.fields(rawRecord, "SHAPE_LAT", "SHAPE_LNG");
        if (window.isEmpty() || location.isEmpty()) {
            return List.of();
        }
        String description = GovMapperSupport.text(rawRecord, "CATEGORYDESCR").orElse("No parking");
        List<com.parkable.model.TimeWindow> windows = (attributes.path("STARTTIME").asInt() == 0
                && attributes.path("ENDTIME").asInt() == 2359) ? List.of() : List.of(window.get());
        return List.of(new MappedRule(new NoParkingRule(GovMapperSupport.metadata("seattle:street-signs:" + unitId.get(),
                description, EnumSet.allOf(DayOfWeek.class), windows)), location.get().latitude(), location.get().longitude()));
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }
}
