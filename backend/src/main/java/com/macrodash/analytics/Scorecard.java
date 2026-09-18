package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;

/**
 * 🩺 종목 스코어카드 — <b>가격으로 잴 수 있는 것만</b>.
 *
 * <p><b>이 카드에 없는 것을 먼저 적습니다.</b> 재무 안정성(부채비율·이자보상배율)과
 * 성장성(매출·이익 증가율)은 여기 없습니다. 이 프로젝트가 재무 데이터를 수집하지
 * 않기 때문입니다. 그래서 이 점수는 <b>종합 점수가 아닙니다</b> — 부실기업도
 * 주가만 오르면 높은 점수를 받습니다. "이 회사가 좋은가"가 아니라 "이 주식이
 * 최근 어떻게 움직였나"를 재는 카드입니다.
 *
 * <p>점수는 <b>백분위</b>입니다. 같은 유니버스(13F 매핑 대형주)의 다른 종목들과
 * 견주어 몇 %에 있는지를 0~100으로 적습니다. 절대 기준(예: "변동성 30% 이하면
 * 80점")을 쓰지 않는 이유는, 그 기준선이 어디서 왔는지 설명할 수 없기
 * 때문입니다. 비교 대상은 화면에 함께 적습니다.
 */
public final class Scorecard {

    private Scorecard() {
    }

    /** 원자료 — 점수로 바꾸기 전의 실제 측정값. 점수만 보여 주면 검증할 수 없습니다. */
    public record Raw(Double momentum1m, Double momentum3m, Double momentum6m,
                      Double momentum12m, Double volatility, Double beta,
                      Double maxDrawdown, Double trendPosition, Integer samples) {
    }

    /**
     * 가격 시계열에서 측정값을 뽑습니다.
     *
     * @param series    종목 일별 종가
     * @param benchmark 베타를 재는 기준 (없으면 베타는 null)
     */
    public static Raw measure(NavigableMap<LocalDate, Double> series,
                              NavigableMap<LocalDate, Double> benchmark) {
        if (series == null || series.size() < 30) {
            return new Raw(null, null, null, null, null, null, null, null,
                    series == null ? 0 : series.size());
        }

        double[] returns = dailyReturns(series);
        Double beta = null;
        if (benchmark != null && benchmark.size() >= 30) {
            double[] paired = pairedBenchmarkReturns(series, benchmark);
            if (paired != null) {
                double[] own = pairedOwnReturns(series, benchmark);
                beta = PortfolioRisk.beta(own, paired);
            }
        }

        return new Raw(
                momentum(series, 21),
                momentum(series, 63),
                momentum(series, 126),
                momentum(series, 252),
                PortfolioRisk.annualizedVolatility(returns),
                beta,
                PortfolioRisk.maxDrawdown(returns),
                trendPosition(series, 200),
                returns.length);
    }

    /**
     * 최근 n 거래일 수익률 (%).
     *
     * <p>달력 기준이 아니라 <b>거래일 기준</b>입니다. 휴장이 낀 구간에서 달력으로
     * 세면 실제보다 짧은 기간을 재게 됩니다.
     */
    static Double momentum(NavigableMap<LocalDate, Double> series, int tradingDays) {
        List<Double> values = new ArrayList<>(series.values());
        if (values.size() <= tradingDays) {
            return null;
        }
        double last = values.get(values.size() - 1);
        double past = values.get(values.size() - 1 - tradingDays);
        return (past <= 0) ? null : (last / past - 1.0) * 100.0;
    }

    /**
     * 이동평균 대비 위치 (%) — 종가가 n일 이동평균보다 몇 % 위/아래인가.
     *
     * <p>추세의 방향을 하나의 숫자로 보는 흔한 방법입니다. 0보다 크면 장기
     * 추세 위에 있습니다.
     */
    static Double trendPosition(NavigableMap<LocalDate, Double> series, int window) {
        List<Double> values = new ArrayList<>(series.values());
        if (values.size() < window) {
            return null;
        }
        double sum = 0.0;
        for (int i = values.size() - window; i < values.size(); i++) {
            sum += values.get(i);
        }
        double average = sum / window;
        return average <= 0 ? null : (values.get(values.size() - 1) / average - 1.0) * 100.0;
    }

    static double[] dailyReturns(NavigableMap<LocalDate, Double> series) {
        List<Double> values = new ArrayList<>(series.values());
        double[] out = new double[Math.max(0, values.size() - 1)];
        for (int i = 1; i < values.size(); i++) {
            double previous = values.get(i - 1);
            out[i - 1] = previous <= 0 ? 0.0 : values.get(i) / previous - 1.0;
        }
        return out;
    }

    private static List<LocalDate> commonDates(NavigableMap<LocalDate, Double> a,
                                               NavigableMap<LocalDate, Double> b) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate date : a.keySet()) {
            if (b.containsKey(date)) {
                out.add(date);
            }
        }
        return out;
    }

    static double[] pairedOwnReturns(NavigableMap<LocalDate, Double> series,
                                     NavigableMap<LocalDate, Double> benchmark) {
        return returnsOn(series, commonDates(series, benchmark));
    }

    static double[] pairedBenchmarkReturns(NavigableMap<LocalDate, Double> series,
                                           NavigableMap<LocalDate, Double> benchmark) {
        return returnsOn(benchmark, commonDates(series, benchmark));
    }

    private static double[] returnsOn(NavigableMap<LocalDate, Double> series, List<LocalDate> dates) {
        if (dates.size() < 2) {
            return null;
        }
        double[] out = new double[dates.size() - 1];
        for (int i = 1; i < dates.size(); i++) {
            double previous = series.get(dates.get(i - 1));
            double current = series.get(dates.get(i));
            if (previous <= 0) {
                return null;
            }
            out[i - 1] = current / previous - 1.0;
        }
        return out;
    }

    /**
     * 유니버스 안에서의 백분위 점수 (0~100).
     *
     * @param higherIsBetter 큰 값이 좋은 지표인가 (모멘텀=true, 변동성·낙폭=false)
     */
    public static Double percentileScore(Double value, List<Double> universe,
                                         boolean higherIsBetter) {
        if (value == null || universe == null) {
            return null;
        }
        List<Double> sorted = new ArrayList<>();
        for (Double one : universe) {
            if (one != null && !Double.isNaN(one)) {
                sorted.add(one);
            }
        }
        // 비교 대상이 너무 적으면 백분위가 의미를 잃습니다. 3개로 낸 "67점"은
        // 숫자만 그럴듯하고 아무것도 말하지 않습니다.
        if (sorted.size() < 10) {
            return null;
        }
        Collections.sort(sorted);

        int below = 0;
        for (Double one : sorted) {
            if (one < value) {
                below++;
            }
        }
        double percentile = (double) below / sorted.size() * 100.0;
        return higherIsBetter ? percentile : 100.0 - percentile;
    }
}
