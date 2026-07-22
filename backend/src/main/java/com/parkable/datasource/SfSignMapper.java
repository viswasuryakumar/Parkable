package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;
import com.parkable.model.TimeLimitRule;

import java.time.Duration;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Maps the structured SFMTA parking-regulations FeatureServer layer. */
public final class SfSignMapper implements GovRuleMapper {
    public static final String PARSER_VERSION = "gov-sf-mapper-v1";
    @Override
    public List<MappedRule> map(JsonNode rawRecord) {
        Optional<String> id = GovMapperSupport.text(rawRecord, "OBJECTID");
        Optional<String> regulation = GovMapperSupport.text(rawRecord, "REGULATION");
        Optional<String> daysValue = GovMapperSupport.text(rawRecord, "DAYS");
        if (id.isEmpty() || regulation.isEmpty() || daysValue.isEmpty()) {
            return List.of();
        }
        Optional<Set<DayOfWeek>> days = GovMapperSupport.compactDays(daysValue.get());
        Optional<com.parkable.model.TimeWindow> window = GovMapperSupport.militaryWindow(rawRecord, "HRS_BEGIN", "HRS_END");
        Optional<GovMapperSupport.Coordinates> location = GovMapperSupport.arcGisLineStart(rawRecord);
        if (days.isEmpty() || window.isEmpty() || location.isEmpty()) {
            return List.of();
        }
        String description = regulation.get();
        String ruleId = "sf:parkingregulations:" + id.get();
        String type = regulation.get().toUpperCase();
        if (type.contains("NO PARK")) {
            return List.of(new MappedRule(new NoParkingRule(GovMapperSupport.metadata(ruleId, description, days.get(),
                    List.of(window.get()))), location.get().latitude(), location.get().longitude()));
        }
        JsonNode limit = GovMapperSupport.attributes(rawRecord).path("HRLIMIT");
        if (type.contains("TIME") && limit.isNumber() && limit.asDouble() > 0) {
            long minutes = Math.round(limit.asDouble() * 60);
            return List.of(new MappedRule(new TimeLimitRule(GovMapperSupport.metadata(ruleId, description, days.get(),
                    List.of(window.get())), Duration.ofMinutes(minutes)), location.get().latitude(), location.get().longitude()));
        }
        return List.of();
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }
}
