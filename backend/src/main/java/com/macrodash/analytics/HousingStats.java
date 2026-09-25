package com.macrodash.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 아파트 실거래 통계 (국토부 실거래가).
 *
 * <p>정의
 * <ul>
 *   <li>평당가 = 거래금액 ÷ 전용면적 × 3.305785 (1평 = 3.305785㎡). 단위 만원/평.
 *       면적이 다른 거래를 비교하려면 총액이 아니라 면적당 가격이어야 합니다.</li>
 *   <li>중위값 — 평균은 초고가 몇 건에 끌려갑니다. 거래 분포가 한쪽으로 긴 부동산에서는
 *       중위값이 "보통 거래"를 더 잘 나타냅니다.</li>
 *   <li>서울 전체 중위값은 <b>모든 구의 거래를 합친</b> 중위값입니다. 구별 중위값의
 *       중위값은 거래가 3건인 구와 500건인 구를 같은 무게로 섞으므로 틀립니다.</li>
 * </ul>
 */
public final class HousingStats {

    public static final double SQM_PER_PYEONG = 3.305785;

    private HousingStats() {
    }

    /** 거래 한 건: 금액(만원), 전용면적(㎡). */
    public record Trade(double amountManwon, double areaSqm) {
        /** 평당가(만원/평). */
        public double pricePerPyeong() {
            return amountManwon / areaSqm * SQM_PER_PYEONG;
        }
    }

    /** 중위값. 비어 있으면 null (0이 아님). */
    public static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    /** 거래들의 평당가 중위값(만원/평). */
    public static Double medianPricePerPyeong(List<Trade> trades) {
        List<Double> values = new ArrayList<>(trades.size());
        for (Trade trade : trades) {
            if (trade.areaSqm() > 0 && trade.amountManwon() > 0) {
                values.add(trade.pricePerPyeong());
            }
        }
        return median(values);
    }

    /** 거래금액 중위값(만원). */
    public static Double medianAmount(List<Trade> trades) {
        return median(trades.stream().map(Trade::amountManwon).toList());
    }

    /**
     * 전년 동월 대비 변화율(%). 어느 한쪽이 없으면 null.
     *
     * <p>전월 대비가 아니라 전년 동월과 비교하는 이유 — 이사철(봄·가을)마다 거래 구성이
     * 달라져 월 단위 비교는 계절 효과가 섞입니다.
     */
    public static Double yoyPct(Double current, Double yearAgo) {
        if (current == null || yearAgo == null || yearAgo <= 0) {
            return null;
        }
        return (current / yearAgo - 1.0) * 100.0;
    }

    /** 테스트·호출부 편의: [[만원, ㎡], ...] 배열을 거래 목록으로. */
    public static List<Trade> trades(double[][] pairs) {
        return Arrays.stream(pairs).map(pair -> new Trade(pair[0], pair[1])).toList();
    }
}
