package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13F 분기 대비 액션 분류 회귀 테스트.
 *
 * <p>화면과 AI 리포트가 같은 분류를 보도록 계산은 서비스 계층에만 둡니다.
 */
class Sec13FServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Sec13FService service = new Sec13FService(null);

    @Test
    @DisplayName("직전 분기에 없던 종목은 신규 매수")
    void newPosition() {
        assertThat(Sec13FService.classify(2.0, 1000.0, 0.0)).contains("신규 매수");
    }

    @Test
    @DisplayName("이번 분기에 사라진 종목은 전량 매도")
    void closedPosition() {
        assertThat(Sec13FService.classify(-2.0, 0.0, 1000.0)).contains("전량 매도");
    }

    @Test
    @DisplayName("비중 증감이 임계치를 넘으면 확대/축소")
    void addedAndReduced() {
        assertThat(Sec13FService.classify(0.5, 1200.0, 1000.0)).contains("비중 확대");
        assertThat(Sec13FService.classify(-0.5, 800.0, 1000.0)).contains("비중 축소");
    }

    @Test
    @DisplayName("미세한 변화는 유지 (반올림 잡음을 매매로 오인하지 않음)")
    void unchangedWithinEpsilon() {
        assertThat(Sec13FService.classify(0.01, 1000.0, 1000.0)).contains("유지");
        assertThat(Sec13FService.classify(-0.01, 1000.0, 1000.0)).contains("유지");
    }

    @Test
    @DisplayName("직전 분기가 없으면 '비교 데이터 없음' — 신규 매수로 단정하지 않는다")
    void withoutPreviousQuarterActionIsUnknown() {
        JsonNode current = quarter("2026-06-30", List.of(
                holding("APPLE INC", "037833100", 5000.0, 100.0, 50.0)));

        List<Map<String, Object>> rows = service.compareQuarters(current, null, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("⚪ 비교 데이터 없음");
        assertThat(rows.get(0).get("weightDiff")).isNull();
    }

    @Test
    @DisplayName("CUSIP은 문자열로 유지된다 (앞자리 0이 사라지면 안 됨)")
    void cusipStaysString() {
        JsonNode current = quarter("2026-06-30", List.of(
                holding("SOME CORP", "037833100", 1000.0, 10.0, 100.0)));

        List<Map<String, Object>> rows = service.compareQuarters(current, null, 10);

        assertThat(rows.get(0).get("cusip")).isInstanceOf(String.class).isEqualTo("037833100");
    }

    @Test
    @DisplayName("직전 분기에만 있던 종목은 전량 매도 행으로 추가된다")
    void closedPositionsAppearAsRows() {
        JsonNode current = quarter("2026-06-30", List.of(
                holding("APPLE INC", "037833100", 5000.0, 100.0, 50.0)));
        JsonNode previous = quarter("2026-03-31", List.of(
                holding("APPLE INC", "037833100", 4000.0, 90.0, 40.0),
                holding("TESLA INC", "88160R101", 3000.0, 80.0, 30.0)));

        List<Map<String, Object>> rows = service.compareQuarters(current, previous, 10);

        Map<String, Object> tesla = rows.stream()
                .filter(row -> "TESLA INC".equals(row.get("name")))
                .findFirst()
                .orElseThrow();

        assertThat(tesla.get("action")).asString().contains("전량 매도");
        assertThat((Double) tesla.get("weight")).isZero();
    }

    @Test
    @DisplayName("보유 종목은 평가액 내림차순으로 정렬된다")
    void sortedByValue() {
        JsonNode current = quarter("2026-06-30", List.of(
                holding("SMALL CO", "111111111", 100.0, 10.0, 1.0),
                holding("BIG CO", "222222222", 9000.0, 20.0, 90.0)));

        List<Map<String, Object>> rows = service.compareQuarters(current, null, 10);

        assertThat(rows.get(0).get("name")).isEqualTo("BIG CO");
    }

    private JsonNode quarter(String reportDate, List<Map<String, Object>> holdings) {
        return mapper.valueToTree(Map.of(
                "filingDate", "2026-08-14",
                "reportDate", reportDate,
                "totalValue", holdings.stream()
                        .mapToDouble(h -> (double) h.get("value")).sum(),
                "holdings", holdings));
    }

    private Map<String, Object> holding(String name, String cusip,
                                        double value, double shares, double weight) {
        return Map.of(
                "name", name, "cusip", cusip, "class", "COM",
                "value", value, "shares", shares, "weight", weight);
    }
}
