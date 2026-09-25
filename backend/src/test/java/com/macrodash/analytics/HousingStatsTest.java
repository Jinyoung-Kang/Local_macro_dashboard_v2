package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HousingStatsTest {

    @Test
    @DisplayName("평당가 = 금액 ÷ 전용면적 × 3.305785")
    void pricePerPyeong() {
        HousingStats.Trade trade = new HousingStats.Trade(100_000, 84.0);
        assertThat(trade.pricePerPyeong()).isCloseTo(100_000 / 84.0 * 3.305785, within(1e-9));
    }

    @Test
    @DisplayName("중위값 — 홀수·짝수·빈 목록(null)")
    void median() {
        assertThat(HousingStats.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(HousingStats.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
        assertThat(HousingStats.median(List.of())).isNull();
    }

    @Test
    @DisplayName("합친 중위값은 구별 중위값의 중위값과 다르다 — 그래서 원자료를 합쳐서 계산")
    void pooledMedianDiffersFromMedianOfMedians() {
        // 구 A: 거래 1건(10), 구 B: 거래 3건(1,1,1), 구 C: 거래 1건(20)
        // 구별 중위값의 중위값 = median(10, 1, 20) = 10 / 합친 중위값 = median(1,1,1,10,20) = 1
        assertThat(HousingStats.median(List.of(10.0, 1.0, 20.0))).isEqualTo(10.0);
        assertThat(HousingStats.median(List.of(10.0, 1.0, 1.0, 1.0, 20.0))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("전년 동월 대비 — 한쪽이 없으면 null")
    void yoy() {
        assertThat(HousingStats.yoyPct(110.0, 100.0)).isCloseTo(10.0, within(1e-9));
        assertThat(HousingStats.yoyPct(110.0, null)).isNull();
        assertThat(HousingStats.yoyPct(null, 100.0)).isNull();
    }
}
