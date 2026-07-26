package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Maps Seattle's sign inventory when its structured day/time fields establish
 * a no-parking restriction. Peak-hour segment records are deliberately
 * skipped: their published PKHRDESC lacks the days of enforcement.
 *
 * <p>Only PNP ("No Parking, but 'standing' allowed") and PNS ("No stopping,
 * standing or parking") map here - both are unambiguously NoParkingRule for
 * a driver asking "can I park here," and both carry the same STARTDAY/
 * ENDDAY/STARTTIME/ENDTIME fields this mapper already reads. CATEGORY has
 * ~35 other live values (verified against the FeatureServer's own
 * CATEGORYDESCR text, not guessed) - most are non-parking signage (street
 * names, warnings, lane control) correctly out of scope, but a handful
 * (PDIS disabled parking, PRZ permit zone, PPP/PTIML short-term paid
 * parking, PCARPL carpool, ...) are genuine parking regulations left
 * unmapped because each needs its own field-by-field verification (what
 * carries the actual duration/hours isn't uniform across categories) rather
 * than a guessed blanket rule.
 */
public final class SeattleSignMapper implements GovRuleMapper {
    public static final String PARSER_VERSION = "gov-seattle-mapper-v1";
    private static final Set<String> NO_PARKING_CATEGORIES = Set.of("PNP", "PNS");

    @Override
    public List<MappedRule> map(JsonNode rawRecord) {
        Optional<String> unitId = GovMapperSupport.text(rawRecord, "UNITID");
        Optional<String> category = GovMapperSupport.text(rawRecord, "CATEGORY");
        Optional<String> status = GovMapperSupport.text(rawRecord, "CURRENT_STATUS");
        if (unitId.isEmpty() || category.isEmpty() || status.isEmpty()
                || !NO_PARKING_CATEGORIES.contains(category.get()) || !"INSVC".equals(status.get())) {
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
