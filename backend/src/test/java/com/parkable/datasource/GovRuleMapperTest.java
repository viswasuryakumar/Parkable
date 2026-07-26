package com.parkable.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkable.model.NoParkingRule;
import com.parkable.model.TimeLimitRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GovRuleMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void nycMapperUsesVerifiedSocrataNamesAndDropsAnUnscheduledLiveRecord() throws IOException {
        JsonNode record = json("""
                {"order_number":"S-01691790","on_street":"WEST HOUSTON STREET",
                "sign_code":"PS-3D","sign_description":"PRESS (SYMBOL) NYP LICENSE PLATES ONLY <-> (SUPERSEDES PS-18E)",
                "sign_x_coord":"982004","sign_y_coord":"204840"}
                """);

        assertThat(new NycSignMapper().map(record)).isEmpty();
        assertThat(new NycSignMapper().parserVersion()).isEqualTo("gov-nyc-mapper-v1");
    }

    @Test
    void nycMapperNormalizesACompleteExplicitRestrictionWithoutGuessing() throws IOException {
        JsonNode record = json("""
                {"order_number":"S-42","sign_description":"NO PARKING MON-FRI 8AM-6PM",
                "sign_x_coord":"982004","sign_y_coord":"204840"}
                """);

        MappedRule mapped = new NycSignMapper().map(record).getFirst();
        NoParkingRule rule = (NoParkingRule) mapped.rule();
        assertThat(rule.metadata().ruleId()).isEqualTo("nyc:nfid-uabd:S-42");
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 20))).isTrue();
        assertThat(rule.metadata().timeWindows()).hasSize(1);
        assertThat(mapped.latitude()).isCloseTo(40.7289, org.assertj.core.data.Offset.offset(0.0002));
        assertThat(mapped.longitude()).isCloseTo(-74.0081, org.assertj.core.data.Offset.offset(0.0002));
    }

    @Test
    void nycMapperParsesIndividualDayNamesNotJustRanges() throws IOException {
        // The real dataset's dominant pattern (alternate-side street
        // cleaning) names specific days, e.g. "MONDAY THURSDAY" - not a
        // "MON-FRI"-style range. Sampled live from Socrata resource
        // nfid-uabd.
        JsonNode record = json("""
                {"order_number":"S-413C","sign_description":
                "NO PARKING (SANITATION BROOM SYMBOL) MONDAY THURSDAY 9AM-10:30AM <-> (SUPERSEDES SP-413C)",
                "sign_x_coord":"982004","sign_y_coord":"204840"}
                """);

        NoParkingRule rule = (NoParkingRule) new NycSignMapper().map(record).getFirst().rule();
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 20))).isTrue(); // a Monday
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 23))).isTrue(); // a Thursday
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 21))).isFalse(); // a Tuesday
    }

    @Test
    void nycMapperTreatsExceptDayAsAnExclusionNotTheOnlyActiveDay() throws IOException {
        // Found live: a plain day-name scan picked up "SUNDAY" from the
        // EXCEPT clause and inverted the sign's meaning entirely - it says
        // "every day EXCEPT Sunday" (Mon-Sat), not "applies on Sunday."
        JsonNode record = json("""
                {"order_number":"S-505C","sign_description":
                "NO PARKING 6AM-8AM EXCEPT SUNDAY <-> (SUPERSEDES SP-505C)",
                "sign_x_coord":"982004","sign_y_coord":"204840"}
                """);

        NoParkingRule rule = (NoParkingRule) new NycSignMapper().map(record).getFirst().rule();
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 20))).isTrue(); // Monday
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 25))).isTrue(); // Saturday
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 26))).isFalse(); // Sunday
    }

    @Test
    void nycMapperTreatsAnyTimeAsEveryDayWithNoTimeWindow() throws IOException {
        JsonNode record = json("""
                {"order_number":"S-854CA","sign_description":"NO PARKING ANYTIME --> (SUPERSEDES SP-854CA)",
                "sign_x_coord":"982004","sign_y_coord":"204840"}
                """);

        NoParkingRule rule = (NoParkingRule) new NycSignMapper().map(record).getFirst().rule();
        assertThat(rule.metadata().timeWindows()).isEmpty();
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 20))).isTrue();
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 26))).isTrue();
    }

    @Test
    void nycMapperStillDropsNoParkingTextWithNeitherDaysNorAnyTime() throws IOException {
        JsonNode record = json("""
                {"order_number":"S-1","sign_description":"NO PARKING",
                "sign_x_coord":"982004","sign_y_coord":"204840"}
                """);

        assertThat(new NycSignMapper().map(record)).isEmpty();
    }

    @Test
    void chicagoAndLaLiveFixturesAreDroppedWhenTheyLackAnEnforcementSchedule() throws IOException {
        JsonNode chicago = json("""
                {"row_id":"14778","status":"ACTIVE","zone":"2228","odd_even":"E",
                "address_range_low":"7716","address_range_high":"7740","street_direction":"W",
                "street_name":"PATTERSON","street_type":"AVE","buffer":"N"}
                """);
        JsonNode laMeter = json("""
                {"spaceid":"LH15","blockface":"200 AVENUE 24","raterange":"$1.50",
                "timelimit":"30MIN","latlng":{"latitude":"34.073151","longitude":"-118.216859"}}
                """);
        JsonNode laSeasonal = json("""
                {"blockface":"501 W ALPINE ST","status":"Q1 2020","signcount":"3",
                "the_geom":{"type":"MultiPolygon","coordinates":[]}}
                """);

        assertThat(new ChicagoSignMapper().map(chicago)).isEmpty();
        assertThat(new LaSignMapper().map(laMeter)).isEmpty();
        assertThat(new LaSignMapper().map(laSeasonal)).isEmpty();
        assertThat(new ChicagoSignMapper().parserVersion()).isEqualTo("gov-chicago-mapper-v1");
        assertThat(new LaSignMapper().parserVersion()).isEqualTo("gov-la-mapper-v1");
    }

    @Test
    void sfMapperNormalizesTheLiveFeatureServerTimeLimitFields() throws IOException {
        JsonNode feature = json("""
                {"attributes":{"OBJECTID":1,"REGULATION":"Time limited","DAYS":"M-Sa",
                "HRS_BEGIN":800,"HRS_END":2100,"HRLIMIT":2.0},
                "geometry":{"paths":[[[-122.404388,37.801553],[-122.403661,37.801646]]]}}
                """);

        MappedRule mapped = new SfSignMapper().map(feature).getFirst();
        TimeLimitRule rule = (TimeLimitRule) mapped.rule();
        assertThat(rule.metadata().ruleId()).isEqualTo("sf:parkingregulations:1");
        assertThat(rule.limit()).isEqualTo(Duration.ofHours(2));
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 25))).isTrue();
        assertThat(mapped.latitude()).isEqualTo(37.801553);
        assertThat(mapped.longitude()).isEqualTo(-122.404388);
        assertThat(new SfSignMapper().parserVersion()).isEqualTo("gov-sf-mapper-v1");
    }

    @Test
    void sfMapperTreatsNoStoppingAndOvernightParkingAsNoParkingRule() throws IOException {
        JsonNode noStopping = sfFeature(2, "No Stopping");
        JsonNode overnight = sfFeature(3, "No overnight parking");

        assertThat(new SfSignMapper().map(noStopping).getFirst().rule()).isInstanceOf(NoParkingRule.class);
        assertThat(new SfSignMapper().map(overnight).getFirst().rule()).isInstanceOf(NoParkingRule.class);
    }

    @Test
    void sfMapperMapsGovernmentPermitToPermitRuleButLeavesPaidOrPermitUnmapped() throws IOException {
        // "Government permit" is unconditionally permit-required (DEPENDS is
        // honest). "Paid + Permit"/"Pay or Permit" are NOT the same thing -
        // paying is a valid alternative to holding a permit there, so
        // PermitRule's DEPENDS would misstate it; left unmapped rather than
        // force-fit until the schema has a real paid-parking concept.
        JsonNode govPermit = sfFeature(4, "Government permit");
        JsonNode paidOrPermit = sfFeature(5, "Pay or Permit");

        com.parkable.model.PermitRule rule =
                (com.parkable.model.PermitRule) new SfSignMapper().map(govPermit).getFirst().rule();
        assertThat(rule.permitZone()).isEqualTo("Government permit");
        assertThat(new SfSignMapper().map(paidOrPermit)).isEmpty();
    }

    @Test
    void sfMapperTreatsOversizedVehicleRestrictionsAsInformationalNotNoParking() throws IOException {
        // Restricts oversized vehicles only - a normal car can still park
        // there, so this must never become a NoParkingRule (the same class
        // of bug "No Double Parking" was).
        JsonNode oversized = sfFeature(6, "No oversized vehicles");

        assertThat(new SfSignMapper().map(oversized).getFirst().rule())
                .isInstanceOf(com.parkable.model.InformationalRule.class);
    }

    private static JsonNode sfFeature(int objectId, String regulation) throws IOException {
        return json("""
                {"attributes":{"OBJECTID":%d,"REGULATION":"%s","DAYS":"M-Sa",
                "HRS_BEGIN":800,"HRS_END":2100},
                "geometry":{"paths":[[[-122.404388,37.801553],[-122.403661,37.801646]]]}}
                """.formatted(objectId, regulation));
    }

    @Test
    void seattleMapperNormalizesTheLiveStreetSignFieldsAndSkipsPeakHourRowsWithoutDays() throws IOException {
        JsonNode sign = json("""
                {"attributes":{"UNITID":"SGN-204962","CATEGORY":"PNP","CURRENT_STATUS":"INSVC",
                "CATEGORYDESCR":"No Parking, but standing allowed","STARTDAY":"1","ENDDAY":"7",
                "STARTTIME":"0","ENDTIME":"2359","SHAPE_LNG":-122.315905,"SHAPE_LAT":47.684466}}
                """);
        JsonNode peakHour = json("""
                {"attributes":{"OBJECTID":19824,"PKHRCODE":"PKAM-L","PKHRDESC":"7-9AM",
                "CURRENT_STATUS":"INSVC","SEGKEY":10179}}
                """);

        MappedRule mapped = new SeattleSignMapper().map(sign).getFirst();
        NoParkingRule rule = (NoParkingRule) mapped.rule();
        assertThat(rule.metadata().ruleId()).isEqualTo("seattle:street-signs:SGN-204962");
        assertThat(rule.metadata().timeWindows()).isEmpty();
        assertThat(mapped.latitude()).isEqualTo(47.684466);
        assertThat(mapped.longitude()).isEqualTo(-122.315905);
        assertThat(new SeattleSignMapper().map(peakHour)).isEmpty();
        assertThat(new SeattleSignMapper().parserVersion()).isEqualTo("gov-seattle-mapper-v1");
    }

    @Test
    void seattleMapperAlsoMapsThePnsNoStoppingCategory() throws IOException {
        JsonNode sign = json("""
                {"attributes":{"UNITID":"SGN-300001","CATEGORY":"PNS","CURRENT_STATUS":"INSVC",
                "CATEGORYDESCR":"No stopping, standing or parking","STARTDAY":"1","ENDDAY":"7",
                "STARTTIME":"0","ENDTIME":"2359","SHAPE_LNG":-122.315905,"SHAPE_LAT":47.684466}}
                """);

        MappedRule mapped = new SeattleSignMapper().map(sign).getFirst();
        assertThat(mapped.rule()).isInstanceOf(NoParkingRule.class);
        assertThat(mapped.rule().metadata().ruleId()).isEqualTo("seattle:street-signs:SGN-300001");
    }

    private static JsonNode json(String value) throws IOException {
        return JSON.readTree(value);
    }
}
