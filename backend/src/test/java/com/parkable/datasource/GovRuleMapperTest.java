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
        assertThat(rule.metadata().ruleId()).isEqualTo("nyc:afgb-4qw7:S-42");
        assertThat(rule.metadata().dayPattern().matches(java.time.LocalDate.of(2026, 7, 20))).isTrue();
        assertThat(rule.metadata().timeWindows()).hasSize(1);
        assertThat(mapped.latitude()).isCloseTo(40.7289, org.assertj.core.data.Offset.offset(0.0002));
        assertThat(mapped.longitude()).isCloseTo(-74.0081, org.assertj.core.data.Offset.offset(0.0002));
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

    private static JsonNode json(String value) throws IOException {
        return JSON.readTree(value);
    }
}
