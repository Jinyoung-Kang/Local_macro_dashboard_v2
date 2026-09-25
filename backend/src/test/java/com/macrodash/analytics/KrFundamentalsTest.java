package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KrFundamentalsTest {

    private static KrFundamentals.Amounts a(Double current, Double previous) {
        return new KrFundamentals.Amounts(current, previous);
    }

    @Test
    @DisplayName("정의대로 계산한다: 부채비율·매출/영업이익 증가율·영업이익률·ROE")
    void computesDefinitions() {
        Map<String, Object> m = KrFundamentals.metrics(Map.of(
                "부채총계", a(150.0, null),
                "자본총계", a(100.0, null),
                "매출액", a(1200.0, 1000.0),
                "영업이익", a(120.0, 100.0),
                "당기순이익", a(80.0, 60.0)));

        assertThat((Double) m.get("debtRatio")).isCloseTo(150.0, within(1e-9));
        assertThat((Double) m.get("revenueGrowth")).isCloseTo(20.0, within(1e-9));
        assertThat((Double) m.get("operatingIncomeGrowth")).isCloseTo(20.0, within(1e-9));
        assertThat((Double) m.get("operatingMargin")).isCloseTo(10.0, within(1e-9));
        assertThat((Double) m.get("roe")).isCloseTo(80.0, within(1e-9));
        assertThat(m.get("operatingTurn")).isNull();
        // 계산할 수 없는 지표는 이유와 함께 missing에 남습니다.
        assertThat((List<String>) m.get("missing")).containsExactly("이자보상배율(주요계정에 이자비용 없음)");
    }

    @Test
    @DisplayName("자본잠식이면 부채비율·ROE를 내지 않는다")
    void capitalImpairment() {
        Map<String, Object> m = KrFundamentals.metrics(Map.of(
                "부채총계", a(500.0, null), "자본총계", a(-10.0, null), "당기순이익", a(-5.0, null)));
        assertThat(m.get("capitalImpaired")).isEqualTo(true);
        assertThat(m.get("debtRatio")).isNull();
        assertThat(m.get("roe")).isNull();
    }

    @Test
    @DisplayName("적자 구간을 지나는 증가율은 숫자 대신 전환 여부로 말한다")
    void turnsInsteadOfMisleadingGrowth() {
        Map<String, Object> toProfit = KrFundamentals.metrics(Map.of("영업이익", a(50.0, -100.0)));
        assertThat(toProfit.get("operatingIncomeGrowth")).isNull();
        assertThat(toProfit.get("operatingTurn")).isEqualTo("흑자전환");

        assertThat(KrFundamentals.metrics(Map.of("영업이익", a(-1.0, 10.0))).get("operatingTurn")).isEqualTo("적자전환");
        assertThat(KrFundamentals.metrics(Map.of("영업이익", a(-1.0, -10.0))).get("operatingTurn")).isEqualTo("적자지속");
    }

    @Test
    @DisplayName("계정이 없으면 0이 아니라 null + missing")
    void missingAccountsAreNull() {
        Map<String, Object> m = KrFundamentals.metrics(Map.of());
        assertThat(m.get("debtRatio")).isNull();
        assertThat(m.get("revenueGrowth")).isNull();
        assertThat((List<String>) m.get("missing")).contains("부채총계", "자본총계", "매출액");
    }

    @Test
    @DisplayName("PER·PBR — 적자·자본잠식·외화 보고는 배수를 내지 않는다")
    void valuation() {
        Map<String, Object> ok = KrFundamentals.valuation(1000.0, 100.0, 500.0, "KRW");
        assertThat((Double) ok.get("per")).isCloseTo(10.0, within(1e-9));
        assertThat((Double) ok.get("pbr")).isCloseTo(2.0, within(1e-9));

        Map<String, Object> loss = KrFundamentals.valuation(1000.0, -5.0, -1.0, null);
        assertThat(loss.get("per")).isNull();
        assertThat(loss.get("pbr")).isNull();

        Map<String, Object> usd = KrFundamentals.valuation(1000.0, 100.0, 500.0, "USD");
        assertThat(usd.get("per")).isNull();
        assertThat(usd.get("valuationNote")).asString().contains("USD");
    }
}
