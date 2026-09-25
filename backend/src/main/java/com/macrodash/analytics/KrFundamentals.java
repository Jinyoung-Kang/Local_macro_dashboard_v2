package com.macrodash.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 국내 종목 재무 안정성·성장성 (DART 사업보고서 주요계정).
 *
 * <p>수집기가 DART 계정·금액을 그대로 저장하면 여기서 비율을 계산합니다.
 * 계산은 전부 순수 함수라 저장소 없이 테스트합니다.
 *
 * <p>정의 (모두 사업보고서 기말·연간 기준, %)
 * <ul>
 *   <li>부채비율 = 부채총계 ÷ 자본총계 × 100</li>
 *   <li>매출 증가율 = (당기 매출액 ÷ 전기 매출액 − 1) × 100</li>
 *   <li>영업이익 증가율 = (당기 영업이익 ÷ 전기 영업이익 − 1) × 100 — 둘 다 흑자일 때만</li>
 *   <li>영업이익률 = 영업이익 ÷ 매출액 × 100</li>
 *   <li>ROE = 당기순이익 ÷ 기말 자본총계 × 100 (평균 자본이 아닌 기말 자본 — 단순화)</li>
 * </ul>
 *
 * <p>주의사항
 * <ul>
 *   <li>값을 만들 수 없으면 null이고, 이유를 {@code notes}에 적습니다. 0으로 채우지 않습니다.</li>
 *   <li>자본총계가 0 이하(자본잠식)면 부채비율·ROE는 의미가 없어 null + 표시.</li>
 *   <li>적자 구간을 지나는 증가율은 부호가 뒤집혀 오해를 부르므로 숫자 대신
 *       "흑자전환/적자전환/적자지속"으로 말합니다.</li>
 *   <li>이자보상배율은 주요계정에 이자비용이 없어 계산할 수 없습니다
 *       ({@code missing}에 적습니다).</li>
 *   <li>금융업(은행·보험)은 매출액 계정이 없어 성장성 지표가 비는 것이 정상입니다.</li>
 * </ul>
 */
public final class KrFundamentals {

    public static final String DEBT = "부채총계";
    public static final String EQUITY = "자본총계";
    public static final String REVENUE = "매출액";
    public static final String OPERATING_INCOME = "영업이익";
    public static final String NET_INCOME = "당기순이익";

    private KrFundamentals() {
    }

    /** 한 계정의 당기·전기 금액. */
    public record Amounts(Double current, Double previous) {
    }

    /**
     * 계정표로 지표를 계산합니다.
     *
     * @param accounts 정규화된 계정명 → 금액 (없는 계정은 키가 없음)
     * @return 지표(값 또는 null), 흑자·적자 전환 표시, 계산하지 못한 이유
     */
    public static Map<String, Object> metrics(Map<String, Amounts> accounts) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        Double debt = current(accounts, DEBT);
        Double equity = current(accounts, EQUITY);
        Double revenue = current(accounts, REVENUE);
        Double operating = current(accounts, OPERATING_INCOME);
        Double net = current(accounts, NET_INCOME);

        boolean impaired = equity != null && equity <= 0;
        out.put("capitalImpaired", impaired);
        if (impaired) {
            notes.add("자본총계가 0 이하(자본잠식) — 부채비율·ROE 계산 불가");
        }

        out.put("debtRatio", impaired ? null : ratio(debt, equity));
        out.put("roe", impaired ? null : ratio(net, equity));
        out.put("operatingMargin", revenue != null && revenue > 0 ? ratio(operating, revenue) : null);
        out.put("revenueGrowth", growth(accounts.get(REVENUE), "매출", notes));

        Amounts op = accounts.get(OPERATING_INCOME);
        out.put("operatingIncomeGrowth", growth(op, "영업이익", notes));
        out.put("operatingTurn", turn(op));

        for (String name : List.of(DEBT, EQUITY, REVENUE, OPERATING_INCOME, NET_INCOME)) {
            if (current(accounts, name) == null) {
                missing.add(name);
            }
        }
        missing.add("이자보상배율(주요계정에 이자비용 없음)");
        out.put("missing", missing);
        out.put("notes", notes);
        return out;
    }

    /** 분자 ÷ 분모 × 100. 어느 쪽이든 없거나 분모가 0이면 null. */
    static Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return numerator / denominator * 100.0;
    }

    /**
     * 전기 대비 증가율. 전기·당기 모두 양수일 때만 숫자를 냅니다.
     *
     * <p>전기가 음수면 "−100억 → +50억"이 −150%처럼 보여 방향이 거꾸로 읽힙니다.
     */
    static Double growth(Amounts amounts, String label, List<String> notes) {
        if (amounts == null || amounts.current() == null || amounts.previous() == null) {
            return null;
        }
        if (amounts.previous() <= 0 || amounts.current() < 0) {
            notes.add(label + " 증가율 — 적자·0 구간이라 비율 대신 전환 여부로 표시");
            return null;
        }
        return (amounts.current() / amounts.previous() - 1.0) * 100.0;
    }

    /** 흑자전환 · 적자전환 · 적자지속 · null(둘 다 흑자이거나 자료 없음). */
    static String turn(Amounts amounts) {
        if (amounts == null || amounts.current() == null || amounts.previous() == null) {
            return null;
        }
        boolean was = amounts.previous() > 0;
        boolean is = amounts.current() > 0;
        if (!was && is) {
            return "흑자전환";
        }
        if (was && !is) {
            return "적자전환";
        }
        if (!was) {
            return "적자지속";
        }
        return null;
    }

    private static Double current(Map<String, Amounts> accounts, String name) {
        Amounts amounts = accounts.get(name);
        return amounts == null ? null : amounts.current();
    }
}
