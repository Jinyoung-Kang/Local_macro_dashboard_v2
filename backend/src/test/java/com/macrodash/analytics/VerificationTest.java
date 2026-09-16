package com.macrodash.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교차 검증 판정 규칙 회귀 테스트.
 *
 * <p>가장 중요한 성질: <b>"확인 못 함"을 "일치"로 위장하지 않는다.</b>
 * 키가 없거나 수집이 실패해 비교를 못 한 것을 일치로 표시하면 검증 자체가
 * 거짓말이 됩니다.
 */
class VerificationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("허용 오차 안이면 일치")
    void matchesWithinTolerance() {
        Verification.Result result = Verification.compare(
                "선물 종가",
                List.of(
                        new Verification.Reading("KRX", true, 1088.30, ""),
                        new Verification.Reading("KIS", true, 1088.35, "")),
                Verification.TOLERANCE_PRICE_PCT, null);

        assertThat(result.verdict()).isEqualTo(Verification.MATCH);
        assertThat(result.label()).isEqualTo("일치");
    }

    @Test
    @DisplayName("허용 오차를 넘으면 불일치")
    void mismatchOutsideTolerance() {
        Verification.Result result = Verification.compare(
                "선물 종가",
                List.of(
                        new Verification.Reading("KRX", true, 1088.30, ""),
                        new Verification.Reading("KIS", true, 1100.00, "")),
                Verification.TOLERANCE_PRICE_PCT, null);

        assertThat(result.verdict()).isEqualTo(Verification.MISMATCH);
        assertThat(result.note()).contains("서로 다른 값");
    }

    @Test
    @DisplayName("비교할 값이 하나뿐이고 실패도 없으면 '확인 못 함' (일치가 아님)")
    void singleReadingIsSkippedNotMatch() {
        Verification.Result result = Verification.compare(
                "선물 종가",
                List.of(new Verification.Reading("KRX", true, 1088.30, "")),
                Verification.TOLERANCE_PRICE_PCT, "KIS 키가 없습니다.");

        assertThat(result.verdict()).isEqualTo(Verification.SKIPPED);
        assertThat(result.verdict()).isNotEqualTo(Verification.MATCH);
        assertThat(result.note()).isEqualTo("KIS 키가 없습니다.");
    }

    @Test
    @DisplayName("한쪽이 수집 실패면 '수집 실패' (일치가 아님)")
    void failedReadingIsError() {
        Verification.Result result = Verification.compare(
                "선물 종가",
                List.of(
                        new Verification.Reading("KRX", true, 1088.30, ""),
                        Verification.Reading.failed("KIS", "토큰 발급 실패")),
                Verification.TOLERANCE_PRICE_PCT, null);

        assertThat(result.verdict()).isEqualTo(Verification.ERROR);
        assertThat(result.label()).isEqualTo("수집 실패");
    }

    @Test
    @DisplayName("장중에는 시세 대조를 하지 않는다 (KRX=확정 종가, KIS=현재가)")
    void priceComparisonIsGatedDuringSession() {
        ZonedDateTime duringSession = ZonedDateTime.of(
                LocalDateTime.of(2026, 9, 11, 11, 0), KST);

        Verification.Gate gate = Verification.settledGate(duringSession);

        assertThat(gate.allowed()).isFalse();
        assertThat(gate.reason()).contains("장중");
    }

    @Test
    @DisplayName("장 마감 후에는 시세 대조가 가능하다")
    void priceComparisonAllowedAfterClose() {
        ZonedDateTime afterClose = ZonedDateTime.of(
                LocalDateTime.of(2026, 9, 11, 17, 0), KST);

        assertThat(Verification.settledGate(afterClose).allowed()).isTrue();
    }

    @Test
    @DisplayName("주말은 확정 데이터라 대조 가능")
    void weekendIsSettled() {
        ZonedDateTime saturday = ZonedDateTime.of(
                LocalDateTime.of(2026, 9, 12, 10, 0), KST);

        assertThat(Verification.settledGate(saturday).allowed()).isTrue();
    }

    @Test
    @DisplayName("수급 대조는 시간 조건이 정반대 — 정규장에만 가능")
    void rankingComparisonRequiresRegularSession() {
        ZonedDateTime duringSession = ZonedDateTime.of(
                LocalDateTime.of(2026, 9, 11, 11, 0), KST);
        ZonedDateTime afterClose = ZonedDateTime.of(
                LocalDateTime.of(2026, 9, 11, 17, 0), KST);

        assertThat(Verification.intradayGate(duringSession).allowed()).isTrue();
        assertThat(Verification.intradayGate(afterClose).allowed()).isFalse();
        assertThat(Verification.intradayGate(afterClose).reason()).contains("장중 전용");
    }

    @Test
    @DisplayName("리포트 요약은 판정별 건수를 섞지 않는다")
    void reportCountsEachVerdictSeparately() {
        Verification.Report report = Verification.Report.of("2026-09-11T17:00:00+09:00", List.of(
                Verification.compare("A", List.of(
                        new Verification.Reading("x", true, 1.0, ""),
                        new Verification.Reading("y", true, 1.0, "")), 0.5, null),
                Verification.compare("B", List.of(
                        new Verification.Reading("x", true, 1.0, ""),
                        new Verification.Reading("y", true, 2.0, "")), 0.5, null),
                Verification.skipped("C", "장중입니다"),
                Verification.compare("D", List.of(
                        Verification.Reading.failed("x", "실패"),
                        new Verification.Reading("y", true, 1.0, "")), 0.5, null)));

        assertThat(report.matchCount()).isEqualTo(1);
        assertThat(report.mismatchCount()).isEqualTo(1);
        assertThat(report.skippedCount()).isEqualTo(1);
        assertThat(report.errorCount()).isEqualTo(1);
        assertThat(report.headline()).isEqualTo("일치 1 · 불일치 1 · 수집 실패 1 · 확인 못 함 1");
    }
}
