package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 시계열 계산 헬퍼.
 *
 * <p><b>0.0과 "데이터 없음"을 절대 섞지 않습니다.</b> 구버전 섹터 화면은 표본이
 * 부족하면 수익률을 0.0으로 돌려줬는데, 화면에서 "0.00%"는 '데이터 없음'이
 * 아니라 '보합'으로 읽힙니다. 신규 상장 ETF의 1년 수익률이 실제로 보합인 것처럼
 * 표시되고 순위 계산에도 섞여 들어갔습니다. 여기서는 그런 경우 {@code null}을
 * 돌려주고 화면이 "—"로 표시합니다.
 */
public final class SeriesMath {

    private SeriesMath() {
    }

    /** n거래일 전 대비 수익률(%). 표본이 부족하거나 과거 가격이 0이면 null. */
    public static Double periodReturn(List<Double> closes, int days) {
        if (closes == null || closes.size() <= days || days < 0) {
            return null;
        }
        double current = closes.get(closes.size() - 1);
        double past = closes.get(closes.size() - 1 - days);
        if (past == 0.0) {
            return null;
        }
        return (current / past - 1.0) * 100.0;
    }

    /** 연초 대비 수익률(%). 해당 연도 표본이 없으면 null. */
    public static Double yearToDateReturn(List<LocalDate> dates, List<Double> closes, int year) {
        if (dates == null || closes == null || dates.size() != closes.size() || closes.isEmpty()) {
            return null;
        }
        Double first = null;
        for (int i = 0; i < dates.size(); i++) {
            if (dates.get(i).getYear() == year) {
                first = closes.get(i);
                break;
            }
        }
        if (first == null || first == 0.0) {
            return null;
        }
        return (closes.get(closes.size() - 1) / first - 1.0) * 100.0;
    }

    /** 표본 내 백분위(0~100). "지금이 역사적으로 어디쯤인지"를 봅니다. */
    public static Double percentile(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        double last = values.get(values.size() - 1);
        long lessOrEqual = values.stream().filter(v -> v <= last).count();
        return (double) lessOrEqual / values.size() * 100.0;
    }

    /** 마지막 값. 없으면 null. */
    public static Double last(List<Double> values) {
        return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** 끝에서 두 번째 값. 없으면 null. */
    public static Double previous(List<Double> values) {
        return values == null || values.size() < 2 ? null : values.get(values.size() - 2);
    }

    /** a - b. 둘 중 하나라도 없으면 null (0으로 메우지 않습니다). */
    public static Double difference(Double a, Double b) {
        return (a == null || b == null) ? null : a - b;
    }

    /** 변화율(%). 기준값이 0이거나 없으면 null. */
    public static Double percentChange(Double current, Double previous) {
        if (current == null || previous == null || previous == 0.0) {
            return null;
        }
        return (current - previous) / previous * 100.0;
    }

    /** 이동 구간 최소/최대 사이에서의 위치(0~100). */
    public static Double rangePosition(List<Double> window, double value) {
        if (window == null || window.isEmpty()) {
            return null;
        }
        double min = window.stream().mapToDouble(Double::doubleValue).min().orElse(value);
        double max = window.stream().mapToDouble(Double::doubleValue).max().orElse(value);
        double span = (max - min) == 0 ? 1 : (max - min);
        return (value - min) / span * 100.0;
    }

    /** 기간 문자열("1mo","1y"…)에 해당하는 일수. 모르면 null. */
    public static Integer periodDays(String period) {
        if (period == null) {
            return null;
        }
        return switch (period) {
            case "1d" -> 1;
            case "5d" -> 5;
            case "1mo" -> 31;
            case "3mo" -> 92;
            case "6mo" -> 183;
            case "1y" -> 366;
            case "2y" -> 731;
            case "5y" -> 1827;
            default -> null;
        };
    }

    /** 최근 n일 구간만 남깁니다. 남는 표본이 2개 미만이면 원본을 그대로 돌려줍니다. */
    public static <T> List<T> tailByDays(List<LocalDate> dates, List<T> values, Integer days) {
        if (days == null || dates == null || values == null
                || dates.size() != values.size() || dates.isEmpty()) {
            return values;
        }
        LocalDate cutoff = dates.get(dates.size() - 1).minusDays(days);
        List<T> out = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            if (!dates.get(i).isBefore(cutoff)) {
                out.add(values.get(i));
            }
        }
        return out.size() >= 2 ? out : values;
    }
}
