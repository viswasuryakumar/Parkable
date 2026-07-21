package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;
import com.parkable.model.Rule;

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
    @Override
    public List<Rule> map(JsonNode rawRecord) {
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
        if (window.isEmpty()) {
            return List.of();
        }
        String description = GovMapperSupport.text(rawRecord, "CATEGORYDESCR").orElse("No parking");
        List<com.parkable.model.TimeWindow> windows = (attributes.path("STARTTIME").asInt() == 0
                && attributes.path("ENDTIME").asInt() == 2359) ? List.of() : List.of(window.get());
        return List.of(new NoParkingRule(GovMapperSupport.metadata("seattle:street-signs:" + unitId.get(),
                description, EnumSet.allOf(DayOfWeek.class), windows)));
    }

    @Override
    public String parserVersion() {
        return "gov-seattle-mapper-v1";
    }
}
