package com.parkable.builder;

import com.parkable.model.ArrowDirection;
import com.parkable.model.DateRange;
import com.parkable.model.DayPattern;
import com.parkable.model.DirectionalModifier;
import com.parkable.model.HolidayPolicy;
import com.parkable.model.NoParkingRule;
import com.parkable.model.NthWeekdayOfMonth;
import com.parkable.model.PermitRule;
import com.parkable.model.Rule;
import com.parkable.model.RuleMetadata;
import com.parkable.model.Side;
import com.parkable.model.SpecificDays;
import com.parkable.model.TimeLimitRule;
import com.parkable.model.TimeWindow;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fluent construction for tests. Production parsing goes through Stage B's
 * RuleFactory instead — the builder's defaults would mask extraction gaps.
 */
public final class RuleBuilder {

    private enum Type { NO_PARKING, TIME_LIMIT, PERMIT }

    private Type type = Type.NO_PARKING;
    private String ruleId = "test-rule";
    private String description = "test rule";
    private DayPattern dayPattern = SpecificDays.anyDay();
    private final List<TimeWindow> windows = new ArrayList<>();
    private Optional<DateRange> dateRange = Optional.empty();
    private HolidayPolicy holidayPolicy = HolidayPolicy.notSuspended();
    private DirectionalModifier direction = DirectionalModifier.unspecified();
    private Duration limit = Duration.ofHours(2);
    private String permitZone = "A";

    public RuleBuilder noParking() {
        this.type = Type.NO_PARKING;
        return this;
    }

    public RuleBuilder timeLimit(Duration limit) {
        this.type = Type.TIME_LIMIT;
        this.limit = limit;
        return this;
    }

    public RuleBuilder permit(String zone) {
        this.type = Type.PERMIT;
        this.permitZone = zone;
        return this;
    }

    public RuleBuilder withId(String ruleId) {
        this.ruleId = ruleId;
        return this;
    }

    public RuleBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public RuleBuilder onDays(DayOfWeek first, DayOfWeek... rest) {
        this.dayPattern = new SpecificDays(Set.copyOf(java.util.EnumSet.of(first, rest)));
        return this;
    }

    public RuleBuilder onNoDays() {
        this.dayPattern = new SpecificDays(Set.of());
        return this;
    }

    public RuleBuilder onAnyDay() {
        this.dayPattern = SpecificDays.anyDay();
        return this;
    }

    public RuleBuilder onNthWeekday(DayOfWeek weekday, Integer... occurrences) {
        this.dayPattern = new NthWeekdayOfMonth(weekday, Set.of(occurrences));
        return this;
    }

    public RuleBuilder duringWindow(LocalTime start, LocalTime end) {
        this.windows.add(new TimeWindow(start, end));
        return this;
    }

    public RuleBuilder anyTime() {
        this.windows.clear();
        return this;
    }

    public RuleBuilder effectiveBetween(LocalDate effective, LocalDate sunset) {
        this.dateRange = Optional.of(DateRange.between(effective, sunset));
        return this;
    }

    public RuleBuilder effectiveFrom(LocalDate effective) {
        this.dateRange = Optional.of(DateRange.from(effective));
        return this;
    }

    public RuleBuilder suspendedOnHolidays() {
        this.holidayPolicy = HolidayPolicy.suspended();
        return this;
    }

    public RuleBuilder onSide(Side side) {
        this.direction = new DirectionalModifier(side, direction.arrow());
        return this;
    }

    public RuleBuilder withArrow(ArrowDirection arrow) {
        this.direction = new DirectionalModifier(direction.side(), arrow);
        return this;
    }

    public Rule build() {
        RuleMetadata metadata = new RuleMetadata(
                ruleId, description, dayPattern, windows, dateRange, holidayPolicy, direction);
        return switch (type) {
            case NO_PARKING -> new NoParkingRule(metadata);
            case TIME_LIMIT -> new TimeLimitRule(metadata, limit);
            case PERMIT -> new PermitRule(metadata, permitZone);
        };
    }
}
