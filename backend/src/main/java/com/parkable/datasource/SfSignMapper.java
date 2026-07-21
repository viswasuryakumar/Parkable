package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkable.model.NoParkingRule;
import com.parkable.model.Rule;
import com.parkable.model.TimeLimitRule;

import java.time.Duration;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Maps the structured SFMTA parking-regulations FeatureServer layer. */
public final class SfSignMapper implements GovRuleMapper {
    @Override
    public List<Rule> map(JsonNode rawRecord) {
        Optional<String> id = GovMapperSupport.text(rawRecord, "OBJECTID");
        Optional<String> regulation = GovMapperSupport.text(rawRecord, "REGULATION");
        Optional<String> daysValue = GovMapperSupport.text(rawRecord, "DAYS");
        if (id.isEmpty() || regulation.isEmpty() || daysValue.isEmpty()) {
            return List.of();
        }
        Optional<Set<DayOfWeek>> days = GovMapperSupport.compactDays(daysValue.get());
        Optional<com.parkable.model.TimeWindow> window = GovMapperSupport.militaryWindow(rawRecord, "HRS_BEGIN", "HRS_END");
        if (days.isEmpty() || window.isEmpty()) {
            return List.of();
        }
        String description = regulation.get();
        String ruleId = "sf:parkingregulations:" + id.get();
        String type = regulation.get().toUpperCase();
        if (type.contains("NO PARK")) {
            return List.of(new NoParkingRule(GovMapperSupport.metadata(ruleId, description, days.get(), List.of(window.get()))));
        }
        JsonNode limit = GovMapperSupport.attributes(rawRecord).path("HRLIMIT");
        if (type.contains("TIME") && limit.isNumber() && limit.asDouble() > 0) {
            long minutes = Math.round(limit.asDouble() * 60);
            return List.of(new TimeLimitRule(GovMapperSupport.metadata(ruleId, description, days.get(), List.of(window.get())),
                    Duration.ofMinutes(minutes)));
        }
        return List.of();
    }

    @Override
    public String parserVersion() {
        return "gov-sf-mapper-v1";
    }
}
