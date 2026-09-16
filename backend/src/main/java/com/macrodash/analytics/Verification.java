package com.macrodash.analytics;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 교차 검증 판정 규칙.
 *
 * <p>이 프로젝트는 공식 API와 비공식 스크래핑을 섞어 씁니다. 비공식 소스는 대상
 * 페이지 구조가 바뀌면 <b>예외를 던지지 않고 조용히 틀린 값</b>을 주기 시작하고,
 * 화면만 봐서는 알아챌 방법이 없습니다. 그래서 "같은 것을 재는 독립된 두 출처"를
 * 붙여 놓고 값이 갈라지는 순간을 잡습니다.
 *
 * <p><b>핵심 원칙 — "확인 못 함"과 "일치"를 절대 섞지 않습니다.</b> 키가 없거나
 * 한쪽 수집이 실패해 비교 자체를 못 한 경우를 "일치"로 표시하면 검증이
 * 거짓말이 됩니다. 판정은 네 가지로 구분됩니다.
 */
public final class Verification {

    private Verification() {
    }

    public static final String MATCH = "match";        // 허용 오차 안에서 일치
    public static final String MISMATCH = "mismatch";  // 갈라짐 → 조사 필요
    public static final String SKIPPED = "skipped";    // 비교 불가 (키 없음/장 시간)
    public static final String ERROR = "error";        // 한쪽 이상 수집 실패

    /** 확정 종가끼리라면 사실상 같아야 합니다. */
    public static final double TOLERANCE_PRICE_PCT = 0.5;
    /** KRX 확정 집계와 KIS HTS 표시 기준이 미세하게 달라 여유를 둡니다. */
    public static final double TOLERANCE_OI_PCT = 2.0;
    /** 등락률은 비율값이라 상대 오차가 아니라 절대 %p 차이로 봅니다. */
    public static final double TOLERANCE_CHANGE_PP = 0.05;

    public record Reading(String source, boolean ok, Double value, String detail) {
        public static Reading failed(String source, String detail) {
            return new Reading(source, false, null, detail);
        }
    }

    public record Result(String name, String verdict, List<Reading> readings,
                         Double tolerancePct, Double diffPct, String note) {
        public String label() {
            return switch (verdict) {
                case MATCH -> "일치";
                case MISMATCH -> "불일치";
                case SKIPPED -> "확인 못 함";
                case ERROR -> "수집 실패";
                default -> verdict;
            };
        }
    }

    /**
     * 두 개 이상의 읽기값을 비교합니다.
     *
     * <p>비교 가능한 값이 2개 미만이면 {@link #SKIPPED}(실패가 있으면 {@link #ERROR})
     * 입니다. 이때를 "일치"로 처리하면 검증이 거짓말을 하게 됩니다.
     */
    public static Result compare(String name, List<Reading> readings,
                                 double tolerancePct, String skipNote) {
        List<Reading> usable = readings.stream()
                .filter(r -> r.ok() && r.value() != null)
                .toList();

        if (usable.size() < 2) {
            boolean anyFailed = readings.stream().anyMatch(r -> !r.ok());
            String note = skipNote;
            if (anyFailed && (note == null || note.isBlank())) {
                note = "비교하려면 최소 두 출처가 필요합니다.";
            }
            return new Result(name, anyFailed ? ERROR : SKIPPED, readings,
                    tolerancePct, null, note);
        }

        double low = usable.stream().mapToDouble(Reading::value).min().orElseThrow();
        double high = usable.stream().mapToDouble(Reading::value).max().orElseThrow();
        double base = low == 0 ? 1.0 : Math.abs(low);
        double diffPct = (high - low) / base * 100.0;

        boolean match = diffPct <= tolerancePct;
        String note = match ? "" :
                "출처가 서로 다른 값을 말하고 있습니다. 비공식 소스의 페이지 구조 변경이나 "
                        + "단위 오해를 의심하세요.";

        return new Result(name, match ? MATCH : MISMATCH, readings, tolerancePct, diffPct, note);
    }

    public static Result skipped(String name, String reason) {
        return new Result(name, SKIPPED, List.of(), null, null, reason);
    }

    /**
     * 지금이 '확정 종가끼리 비교해도 되는 시간'인지.
     *
     * <p>KRX는 <b>일별 확정 종가</b>를, KIS는 <b>현재가</b>를 줍니다. 장중에 이 둘을
     * 비교하면 항상 다르게 나오므로 매일 거짓 경보가 울립니다. 장이 닫힌 뒤에만
     * 비교합니다.
     */
    public static Gate settledGate(ZonedDateTime nowKst) {
        if (nowKst.getDayOfWeek() == DayOfWeek.SATURDAY
                || nowKst.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return new Gate(true, "주말 (확정 데이터)");
        }
        LocalTime time = nowKst.toLocalTime();
        if (time.isAfter(LocalTime.of(16, 29))) {
            return new Gate(true, "장 마감 후 (확정 데이터)");
        }
        if (time.isBefore(LocalTime.of(9, 0))) {
            return new Gate(true, "장 시작 전 (전 거래일 확정 데이터)");
        }
        return new Gate(false,
                "장중입니다. KRX는 전 거래일 확정 종가, KIS는 현재가를 주므로 지금 비교하면 "
                        + "항상 다르게 나옵니다. 장 마감 후 다시 확인하세요.");
    }

    /**
     * 지금이 '장중 가집계끼리 비교해도 되는 시간'인지.
     *
     * <p>가격·지수와 <b>시간 조건이 정반대</b>입니다. KIS 가집계 TR은 장중 전용이라
     * 마감 후에는 빈 데이터를 정상적으로 돌려줍니다.
     */
    public static Gate intradayGate(ZonedDateTime nowKst) {
        if (nowKst.getDayOfWeek() == DayOfWeek.SATURDAY
                || nowKst.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return new Gate(false, "주말입니다. 장중 가집계 비교는 정규장에만 가능합니다.");
        }
        LocalTime time = nowKst.toLocalTime();
        boolean open = !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(15, 30));
        return open
                ? new Gate(true, "정규장")
                : new Gate(false,
                "정규장(09:00~15:30)이 아닙니다. KIS 가집계 TR은 장중 전용이라 지금은 "
                        + "빈 데이터를 돌려줍니다.");
    }

    public record Gate(boolean allowed, String reason) {
    }

    /** 검증 1회분 요약. */
    public record Report(String checkedAt, List<Result> results,
                         int matchCount, int mismatchCount,
                         int errorCount, int skippedCount) {

        public static Report of(String checkedAt, List<Result> results) {
            List<Result> safe = new ArrayList<>(results);
            return new Report(
                    checkedAt, safe,
                    count(safe, MATCH), count(safe, MISMATCH),
                    count(safe, ERROR), count(safe, SKIPPED));
        }

        private static int count(List<Result> results, String verdict) {
            return (int) results.stream().filter(r -> verdict.equals(r.verdict())).count();
        }

        public String headline() {
            return "일치 %d · 불일치 %d · 수집 실패 %d · 확인 못 함 %d"
                    .formatted(matchCount, mismatchCount, errorCount, skippedCount);
        }
    }
}
