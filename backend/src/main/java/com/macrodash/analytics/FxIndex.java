package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 💱 단위가 다른 계열을 한 차트에 겹쳐 보기 위한 재기준화(rebasing).
 *
 * <p><b>왜 필요한가</b> — 원/달러(약 1,380), 달러/엔(약 150), 엔/원(약 930),
 * 달러 인덱스(약 100)를 한 축에 그대로 겹치면 축 범위가 0~1,400이 되고, 달러
 * 인덱스와 달러/엔은 바닥에 눌려 직선이 됩니다. 이 프로젝트의 규칙(차트는
 * 축부터 정직해야 합니다)에 따라 두 가지 길만 허용합니다.
 * <ol>
 *   <li><b>기준일 = 100</b> — 각 계열을 창의 첫 값으로 나눠 100으로 맞춥니다.
 *       축이 "기준일 대비 %"가 되어 <b>무엇이 더 많이 움직였는지</b>를 비교할
 *       수 있습니다.</li>
 *   <li><b>원래 단위</b> — 값 자체를 봅니다. 자릿수가 비슷한 계열끼리만
 *       의미가 있고, 그래서 화면이 경고를 띄웁니다.</li>
 * </ol>
 *
 * <p>기준값은 <b>계열마다 각자의 첫 유효값</b>입니다. 공통 시작일을 강제하면
 * 거래일이 하루 다른 계열이 통째로 빠집니다(환율은 시장마다 휴일이 다릅니다).
 * 대신 계열별 기준일을 함께 돌려주어 화면이 그대로 적을 수 있게 합니다.
 */
public final class FxIndex {

    private FxIndex() {
    }

    /** 재기준화 결과. 유효값이 하나도 없으면 baseDate·baseValue가 null입니다. */
    public record Rebased(LocalDate baseDate, Double baseValue,
                          NavigableMap<LocalDate, Double> values) {
    }

    /**
     * 창의 첫 유효값을 100으로 놓고 다시 매깁니다.
     *
     * <p>기준값이 0이면 재기준화가 불가능합니다(나눌 수 없습니다). 그때는
     * 0을 채우거나 1로 바꾸지 않고 <b>빈 결과</b>를 돌려줍니다 — 없는 값을
     * 그럴듯한 숫자로 만들지 않습니다.
     */
    public static Rebased rebase(NavigableMap<LocalDate, Double> series) {
        LocalDate baseDate = null;
        Double baseValue = null;
        for (Map.Entry<LocalDate, Double> entry : series.entrySet()) {
            Double value = entry.getValue();
            if (value != null && value != 0.0 && !Double.isNaN(value)) {
                baseDate = entry.getKey();
                baseValue = value;
                break;
            }
        }

        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        if (baseValue == null) {
            return new Rebased(null, null, out);
        }
        for (Map.Entry<LocalDate, Double> entry : series.entrySet()) {
            Double value = entry.getValue();
            out.put(entry.getKey(), value == null ? null : value / baseValue * 100.0);
        }
        return new Rebased(baseDate, baseValue, out);
    }

    /**
     * 여러 계열의 날짜를 합친 정렬된 목록.
     *
     * <p><b>교집합이 아니라 합집합</b>입니다. 환율 시장은 나라마다 휴일이
     * 달라, 교집합을 쓰면 한 계열이 쉰 날의 다른 계열 값까지 버리게 됩니다.
     * 값이 없는 날은 null로 남고, 차트는 그 자리를 잇지 않습니다(없는 거래를
     * 선으로 이으면 실제로는 없던 흐름이 생깁니다).
     */
    @SafeVarargs
    public static List<LocalDate> unionDates(NavigableMap<LocalDate, Double>... series) {
        TreeMap<LocalDate, Boolean> merged = new TreeMap<>();
        for (NavigableMap<LocalDate, Double> entry : series) {
            for (LocalDate date : entry.keySet()) {
                merged.put(date, Boolean.TRUE);
            }
        }
        return new ArrayList<>(merged.keySet());
    }

    /** 변화율(%) — 창의 첫 값 대비 마지막 값. 둘 중 하나가 없으면 null. */
    public static Double changePct(NavigableMap<LocalDate, Double> series) {
        Rebased rebased = rebase(series);
        if (rebased.baseValue() == null || rebased.values().isEmpty()) {
            return null;
        }
        Double last = rebased.values().lastEntry().getValue();
        return last == null ? null : last - 100.0;
    }
}
