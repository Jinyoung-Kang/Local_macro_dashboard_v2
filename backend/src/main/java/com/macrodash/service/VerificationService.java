package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.analytics.Json;
import com.macrodash.analytics.Verification;
import com.macrodash.collector.CollectorClient;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 🔍 데이터 교차 검증 (KRX · KIS).
 *
 * <p>이 프로젝트는 공식 API와 비공식 스크래핑을 섞어 씁니다. 비공식 소스는
 * 페이지 구조가 바뀌면 조용히 틀린 값을 주기 시작하고, 화면만 봐서는 알아챌
 * 방법이 없습니다. KRX·KIS 두 공식 출처를 기준선으로 두고 같은 수치를 대조합니다.
 *
 * <table>
 *   <caption>대조 항목</caption>
 *   <tr><th>항목</th><th>출처 A</th><th>출처 B</th><th>언제</th></tr>
 *   <tr><td>KOSPI200 선물 종가</td><td>KRX(화면이 쓰는 값)</td><td>KIS</td><td>장 마감 후</td></tr>
 *   <tr><td>미결제약정</td><td>KRX(화면이 쓰는 값)</td><td>KIS</td><td>장 마감 후</td></tr>
 *   <tr><td>선물 등락률</td><td>종가 계산값</td><td>KRX 보고값</td><td>장 마감 후</td></tr>
 *   <tr><td>KOSPI200 현물</td><td>KRX</td><td>KIS · yfinance</td><td>장 마감 후</td></tr>
 *   <tr><td>수급 1위 종목</td><td>KIS 가집계</td><td>Daum(화면이 쓰는 값)</td><td>정규장 중</td></tr>
 * </table>
 *
 * <p><b>시간 조건이 항목마다 반대인 이유</b> — KRX는 일별 확정 종가를, KIS는
 * 현재가를 줍니다. 장중에 비교하면 항상 다르므로 시세 대조는 마감 후에만 합니다.
 * 반대로 KIS 수급 가집계 TR은 장중 전용이라 마감 후에는 빈 데이터를 줍니다.
 */
@Service
public class VerificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StoreReader store;
    private final CollectorClient collector;

    public VerificationService(StoreReader store, CollectorClient collector) {
        this.store = store;
        this.collector = collector;
    }

    public Map<String, Object> run() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        Optional<JsonNode> readings = collector.verificationReadings("KOSPI", "외국인", "순매수");

        if (readings.isEmpty()) {
            return Map.of(
                    "available", false,
                    "message", "수집기에서 검증용 값을 받지 못했습니다. 수집기 상태를 확인하세요.");
        }

        JsonNode payload = readings.get();
        JsonNode keys = Json.child(payload, "keys");
        boolean hasKrx = Json.asBoolean(keys, "krx");
        boolean hasKis = Json.asBoolean(keys, "kis");

        List<Verification.Result> results = new ArrayList<>();
        Verification.Gate settled = Verification.settledGate(now);

        if (!hasKrx && !hasKis) {
            results.add(Verification.skipped("KOSPI200 선물 종가",
                    "KRX·KIS 키가 모두 없어 교차 검증을 할 수 없습니다."));
        } else if (!settled.allowed()) {
            for (String name : List.of(
                    "KOSPI200 선물 종가", "KOSPI200 선물 미결제약정",
                    "선물 등락률 (KRX 보고값 vs 종가 계산값)", "KOSPI200 현물 지수")) {
                results.add(Verification.skipped(name, settled.reason()));
            }
        } else {
            results.add(comparePrice(payload));
            results.add(compareOpenInterest(payload));
            results.add(compareChangeRate());
            results.add(compareIndex(payload));
        }

        results.add(compareRankingTop(payload, now));

        Verification.Report report = Verification.Report.of(now.toString(), results);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("checkedAt", report.checkedAt());
        out.put("headline", report.headline());
        out.put("matchCount", report.matchCount());
        out.put("mismatchCount", report.mismatchCount());
        out.put("errorCount", report.errorCount());
        out.put("skippedCount", report.skippedCount());
        out.put("keys", Map.of("krx", hasKrx, "kis", hasKis));
        out.put("results", report.results().stream().map(this::toMap).toList());
        // 종료 코드 의미를 그대로 유지합니다: 0 불일치 없음 / 1 불일치 발견 / 2 검증 불가
        out.put("exitCode", (!hasKrx && !hasKis) ? 2 : (report.mismatchCount() > 0 ? 1 : 0));
        return out;
    }

    private Map<String, Object> toMap(Verification.Result result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", result.name());
        map.put("verdict", result.verdict());
        map.put("label", result.label());
        map.put("tolerancePct", result.tolerancePct());
        map.put("diffPct", result.diffPct());
        map.put("note", result.note());
        map.put("readings", result.readings().stream().map(reading -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", reading.source());
            entry.put("ok", reading.ok());
            entry.put("value", reading.value());
            entry.put("detail", reading.detail());
            entry.put("asOf", reading.asOf());
            return entry;
        }).toList());
        return map;
    }

    /**
     * 화면이 실제로 쓰고 있는 선물 종가 vs KIS.
     *
     * <p>새로 수집하지 않고 화면과 같은 경로(저장본)를 봅니다. 검증의 목적은
     * "사용자가 보고 있는 숫자가 맞는가"이지 "지금 다시 받으면 뭐가 오는가"가
     * 아니기 때문입니다.
     */
    private Verification.Result comparePrice(JsonNode payload) {
        return Verification.compare(
                "KOSPI200 선물 종가",
                List.of(
                        storedFuturesReading("futuresClose", "KRX (화면이 쓰는 값)"),
                        readingFrom(Json.child(payload, "kisFutures"), "KIS Open API")),
                Verification.TOLERANCE_PRICE_PCT,
                null,
                referenceDate(payload));
    }

    private Verification.Result compareOpenInterest(JsonNode payload) {
        JsonNode kis = Json.child(payload, "kisFutures");
        Double openInterest = Json.asDouble(kis, "openInterest");
        Verification.Reading kisReading = (kis != null && Json.asBoolean(kis, "ok") && openInterest != null)
                ? new Verification.Reading("KIS Open API", true, openInterest, "미결제약정")
                : Verification.Reading.failed("KIS Open API", "응답에 미결제약정이 없습니다");

        return Verification.compare(
                "KOSPI200 선물 미결제약정",
                List.of(storedFuturesReading("openInterest", "KRX (화면이 쓰는 값)"), kisReading),
                Verification.TOLERANCE_OI_PCT,
                null,
                referenceDate(payload));
    }

    /**
     * 선물 등락률: KRX 보고값(FLUC_RT) vs 종가에서 계산한 값.
     *
     * <p>실제로 터졌던 사고입니다. FLUC_RT가 없을 때 파서가 0.0으로 메웠고
     * 화면이 매일 "+0.00%"를 보여줬으며, 국면 판정까지 강세로 뒤집혔습니다.
     */
    private Verification.Result compareChangeRate() {
        String name = "선물 등락률 (KRX 보고값 vs 종가 계산값)";
        Optional<Snapshot> snapshot = store.readStored(Datasets.SNAP_KRX_FUTURES);

        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            return new Verification.Result(name, Verification.ERROR, List.of(), null, null,
                    "선물 시계열이 비어 있습니다.");
        }
        if (isEstimated(snapshot.get())) {
            return Verification.skipped(name,
                    "추정치 모드입니다 (KRX 확정치가 아니므로 대조 대상이 아닙니다).");
        }

        List<JsonNode> rows = Json.array(snapshot.get().payload(), "rows");
        if (rows.isEmpty()) {
            return new Verification.Result(name, Verification.ERROR, List.of(), null, null,
                    "선물 시계열이 비어 있습니다.");
        }

        JsonNode last = rows.get(rows.size() - 1);
        Double derived = Json.asDouble(last, "changePct");
        Double reported = Json.asDouble(last, "changePctReported");

        List<Verification.Reading> readings = List.of(
                derived == null
                        ? Verification.Reading.failed("종가 계산값", "계산 불가")
                        : new Verification.Reading("종가 계산값", true, derived, "연속 확정 종가에서 계산"),
                reported == null
                        ? Verification.Reading.failed("KRX 보고값(FLUC_RT)", "응답에 없음")
                        : new Verification.Reading("KRX 보고값(FLUC_RT)", true, reported, "KRX 응답 필드"));

        if (derived == null) {
            return new Verification.Result(name, Verification.ERROR, readings, null, null,
                    "종가에서 등락률을 계산하지 못했습니다.");
        }
        if (reported == null) {
            return new Verification.Result(name, Verification.SKIPPED, readings, null, null,
                    "KRX 응답에 등락률 필드(FLUC_RT)가 없습니다. 화면은 종가에서 계산한 값을 "
                            + "씁니다. 예전에는 이 경우 0.00%로 메워서 국면 판정이 뒤집혔습니다.");
        }

        // 등락률은 비율값이라 상대 오차가 아니라 절대 %p 차이로 봅니다.
        double gap = Math.abs(derived - reported);
        if (gap <= Verification.TOLERANCE_CHANGE_PP) {
            return new Verification.Result(name, Verification.MATCH, readings, null, gap,
                    "차이 %.3f%%p".formatted(gap));
        }
        return new Verification.Result(name, Verification.MISMATCH, readings, null, gap,
                "차이 %.3f%%p. 둘 중 하나의 파싱이 깨졌습니다. 화면은 종가 계산값을 쓰므로 "
                        .formatted(gap) + "KRX 필드 매핑을 확인하세요.");
    }

    private Verification.Result compareIndex(JsonNode payload) {
        return Verification.compare(
                "KOSPI200 현물 지수",
                List.of(
                        readingFrom(Json.child(payload, "krxIndex"), "KRX Open API"),
                        readingFrom(Json.child(payload, "kisIndex"), "KIS Open API"),
                        readingFrom(Json.child(payload, "yfinanceIndex"), "yfinance ^KS200")),
                Verification.TOLERANCE_PRICE_PCT,
                null,
                referenceDate(payload));
    }

    /**
     * 수급 1위 종목이 KIS와 Daum에서 같은지.
     *
     * <p>금액은 가집계 시점 차이로 조금씩 다를 수 있지만, <b>1위 종목명</b>이
     * 갈라지면 둘 중 하나의 파싱이 깨졌다는 강한 신호입니다.
     */
    private Verification.Result compareRankingTop(JsonNode payload, ZonedDateTime now) {
        String name = "KOSPI 외국인 순매수 1위 종목";
        Verification.Gate gate = Verification.intradayGate(now);
        if (!gate.allowed()) {
            return Verification.skipped(name, gate.reason());
        }

        JsonNode top = Json.child(payload, "rankingTop");
        JsonNode kis = Json.child(top, "kis");
        JsonNode daum = Json.child(top, "daum");

        Verification.Reading kisReading = rankingReading(kis, "KIS 장중 가집계");
        Verification.Reading daumReading = rankingReading(daum, "Daum (화면이 쓰는 값)");
        List<Verification.Reading> readings = List.of(kisReading, daumReading);

        if (!kisReading.ok() || !daumReading.ok()) {
            return new Verification.Result(name, Verification.ERROR, readings, null, null,
                    "양쪽 모두 성공해야 비교할 수 있습니다.");
        }

        String kisTop = Json.asText(kis, "name");
        String daumTop = Json.asText(daum, "name");

        if (kisTop != null && kisTop.equals(daumTop)) {
            return new Verification.Result(name, Verification.MATCH, readings, null, null,
                    "두 출처 모두 1위는 '%s'입니다.".formatted(kisTop));
        }
        return new Verification.Result(name, Verification.MISMATCH, readings, null, null,
                "1위 종목이 다릅니다. KIS='%s' / Daum='%s'. 가집계 시점 차이일 수도 있으나, "
                        .formatted(kisTop, daumTop) + "Daum 파싱이 깨졌을 가능성을 먼저 확인하세요.");
    }

    private Verification.Reading rankingReading(JsonNode node, String source) {
        if (node == null || !Json.asBoolean(node, "ok")) {
            return Verification.Reading.failed(source,
                    node == null ? "응답 없음" : String.valueOf(Json.asText(node, "detail")));
        }
        return new Verification.Reading(source, true, Json.asDouble(node, "value"),
                String.valueOf(Json.asText(node, "name")));
    }

    /**
     * 저장본에서 읽는 값.
     *
     * <p>추정치(is_estimated)는 KRX 값이 아니므로 비교 대상에서 뺍니다. 추정치를
     * KRX 확정치인 양 비교하면 검증 결과가 무의미해집니다.
     */
    private Verification.Reading storedFuturesReading(String field, String source) {
        Optional<Snapshot> snapshot = store.readStored(Datasets.SNAP_KRX_FUTURES);
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            return Verification.Reading.failed(source, "저장본이 비어 있습니다");
        }
        if (isEstimated(snapshot.get())) {
            return Verification.Reading.failed(source,
                    "KODEX 200 기반 추정치입니다 (KRX 확정치 아님)");
        }

        List<JsonNode> rows = Json.array(snapshot.get().payload(), "rows");
        if (rows.isEmpty()) {
            return Verification.Reading.failed(source, "저장본에 행이 없습니다");
        }

        JsonNode last = rows.get(rows.size() - 1);
        Double value = Json.asDouble(last, field);
        if (value == null || value <= 0) {
            return Verification.Reading.failed(source, "값이 없습니다 (" + field + ")");
        }
        String asOf = Json.asText(last, "date");
        return Verification.Reading.dated(source, value,
                "기준일 " + asOf, asOf == null ? null : asOf.substring(0, Math.min(10, asOf.length())));
    }

    private boolean isEstimated(Snapshot snapshot) {
        return snapshot.isEstimated() || Json.asBoolean(snapshot.payload(), "isEstimated");
    }

    private Verification.Reading readingFrom(JsonNode node, String source) {
        if (node == null) {
            return Verification.Reading.failed(source, "응답 없음");
        }
        if (!Json.asBoolean(node, "ok")) {
            return Verification.Reading.failed(source, String.valueOf(Json.asText(node, "detail")));
        }
        // asOf가 없는 출처(KIS 현재가 등)는 '최신값'으로 봅니다.
        return Verification.Reading.dated(source, Json.asDouble(node, "value"),
                String.valueOf(Json.asText(node, "detail")), Json.asText(node, "asOf"));
    }

    /**
     * 이번 검증이 기준으로 삼을 최신 거래일.
     *
     * <p>읽기값들이 말하는 기준일 중 가장 최신을 씁니다. 공휴일 달력을 들고 있지
     * 않아도 되고, "오늘이 거래일인가"를 우리가 추측하지 않아도 됩니다 — 데이터가
     * 스스로 말한 날짜 중 가장 앞선 것이 곧 최신 거래일입니다.
     */
    private String referenceDate(JsonNode payload) {
        String newest = null;
        for (String key : List.of("krxIndex", "kisIndex", "kisFutures", "yfinanceIndex")) {
            String asOf = Json.asText(Json.child(payload, key), "asOf");
            if (asOf != null && !asOf.isBlank() && (newest == null || asOf.compareTo(newest) > 0)) {
                newest = asOf;
            }
        }
        return newest;
    }
}
