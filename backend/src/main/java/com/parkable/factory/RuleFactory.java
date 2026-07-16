package com.parkable.factory;

import com.parkable.extraction.dto.DayPatternDto;
import com.parkable.extraction.dto.DirectionDto;
import com.parkable.extraction.dto.ExceptionDto;
import com.parkable.extraction.dto.ExtractionEnvelope;
import com.parkable.extraction.dto.RuleDto;
import com.parkable.extraction.dto.TimeWindowDto;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps validated extraction DTOs to concrete domain {@link Rule}s. Only ever
 * called on data that already passed schema + semantic validation, so enum
 * strings and required combinations can be assumed legal; anything else is
 * schema/factory drift and fails fast.
 */
public final class RuleFactory {

    private static final Map<String, DayOfWeek> DAY_NAMES = Map.of(
            "MON", DayOfWeek.MONDAY,
            "TUE", DayOfWeek.TUESDAY,
            "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY,
            "FRI", DayOfWeek.FRIDAY,
            "SAT", DayOfWeek.SATURDAY,
            "SUN", DayOfWeek.SUNDAY);

    private RuleFactory() {}

    /** Maps every active rule in the envelope; deprecated/superseded rules are skipped. */
    public static List<Rule> fromEnvelope(ExtractionEnvelope envelope) {
        return envelope.rules().stream()
                .filter(RuleFactory::isActive)
                .map(RuleFactory::from)
                .toList();
    }

    private static boolean isActive(RuleDto dto) {
        return dto.status() == null || "active".equals(dto.status());
    }

    public static Rule from(RuleDto dto) {
        RuleMetadata metadata = metadataOf(dto);
        return switch (dto.type()) {
            // street_cleaning shares no_parking semantics (different day
            // pattern); "restricted" is a catch-all prohibition — mapping it
            // to no-parking is the conservative choice (never a confidently
            // wrong YES).
            case "no_parking", "street_cleaning", "restricted" -> new NoParkingRule(metadata);
            case "time_limit" -> new TimeLimitRule(
                    metadata, Duration.ofMinutes(dto.restriction().durationMinutes()));
            case "permit_required" -> new PermitRule(metadata, dto.restriction().permitType());
            case "color_curb" -> colorCurbRule(dto, metadata);
            default -> throw new UnsupportedSignTypeException(dto.type());
        };
    }

    private static Rule colorCurbRule(RuleDto dto, RuleMetadata metadata) {
        return switch (dto.restriction().color()) {
            // Red = no stopping; white/yellow = loading only, so no general
            // parking while active.
            case "red", "white", "yellow" -> new NoParkingRule(metadata);
            // Blue = disabled parking; the engine can't verify a placard, so
            // permit semantics (DEPENDS) are the honest mapping.
            case "blue" -> new PermitRule(metadata, "disabled placard");
            // Green = short-term parking; semantic validation guarantees the
            // duration is present.
            case "green" -> new TimeLimitRule(
                    metadata, Duration.ofMinutes(dto.restriction().durationMinutes()));
            default -> throw new UnsupportedSignTypeException("color_curb:" + dto.restriction().color());
        };
    }

    private static RuleMetadata metadataOf(RuleDto dto) {
        return new RuleMetadata(
                dto.ruleId(),
                descriptionOf(dto),
                dayPatternOf(dto.dayPattern()),
                timeWindowsOf(dto.timeWindows()),
                dateRangeOf(dto.dayPattern()),
                holidayPolicyOf(dto.exceptions()),
                directionOf(dto.direction()));
    }

    private static String descriptionOf(RuleDto dto) {
        if (dto.description() != null) {
            return dto.description();
        }
        if (dto.originalDescription() != null) {
            return dto.originalDescription();
        }
        return dto.type();
    }

    private static DayPattern dayPatternOf(DayPatternDto dto) {
        return switch (dto.type()) {
            // date_range applies every day within its effectivity bounds; the
            // bounds themselves become the metadata's DateRange.
            case "any_day", "date_range" -> SpecificDays.anyDay();
            case "specific_days" -> specificDaysOf(dto.daysOfWeek());
            case "nth_weekday_of_month" -> new NthWeekdayOfMonth(
                    DAY_NAMES.get(dto.weekday()), Set.copyOf(dto.occurrences()));
            default -> throw new UnsupportedSignTypeException("day_pattern:" + dto.type());
        };
    }

    private static SpecificDays specificDaysOf(List<String> dayNames) {
        if (dayNames.contains("ANY")) {
            return SpecificDays.anyDay();
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String name : dayNames) {
            days.add(DAY_NAMES.get(name));
        }
        return new SpecificDays(days);
    }

    private static List<TimeWindow> timeWindowsOf(List<TimeWindowDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of(); // absent time_windows = ANY TIME
        }
        // One all-day window makes the whole rule ANY TIME regardless of any
        // sibling windows.
        if (dtos.stream().anyMatch(w -> Boolean.TRUE.equals(w.allDay()))) {
            return List.of();
        }
        return dtos.stream()
                .map(w -> new TimeWindow(LocalTime.parse(w.startTime()), LocalTime.parse(w.endTime())))
                .toList();
    }

    private static Optional<DateRange> dateRangeOf(DayPatternDto dto) {
        if (Boolean.TRUE.equals(dto.yearRound()) || dto.effectiveDate() == null) {
            return Optional.empty();
        }
        LocalDate effective = LocalDate.parse(dto.effectiveDate());
        return Optional.of(dto.sunsetDate() == null
                ? DateRange.from(effective)
                : DateRange.between(effective, LocalDate.parse(dto.sunsetDate())));
    }

    private static HolidayPolicy holidayPolicyOf(List<ExceptionDto> exceptions) {
        // Only holiday_suspension is modeled in Phase 1; special_event,
        // snow_emergency and permit_holder exceptions are city-operational
        // signals the engine has no data feed for yet.
        boolean suspended = exceptions != null && exceptions.stream()
                .anyMatch(e -> "holiday_suspension".equals(e.exceptionType()));
        return suspended ? HolidayPolicy.suspended() : HolidayPolicy.notSuspended();
    }

    private static DirectionalModifier directionOf(DirectionDto dto) {
        if (dto == null) {
            return DirectionalModifier.unspecified();
        }
        Side side = switch (dto.sideOfStreet() == null ? "not_specified" : dto.sideOfStreet()) {
            case "left" -> Side.LEFT;
            case "right" -> Side.RIGHT;
            case "both" -> Side.BOTH;
            default -> Side.NOT_SPECIFIED;
        };
        ArrowDirection arrow = switch (dto.arrow() == null ? "not_applicable" : dto.arrow()) {
            case "north" -> ArrowDirection.NORTH;
            case "south" -> ArrowDirection.SOUTH;
            case "east" -> ArrowDirection.EAST;
            case "west" -> ArrowDirection.WEST;
            default -> ArrowDirection.NONE;
        };
        return new DirectionalModifier(side, arrow);
    }
}
