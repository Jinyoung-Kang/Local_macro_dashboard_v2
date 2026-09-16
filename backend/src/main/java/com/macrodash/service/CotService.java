package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.analytics.Json;
import com.macrodash.analytics.SeriesMath;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 🏛️ 글로벌 투기세력 (CFTC COT).
 *
 * <p>비상업(투기·스마트머니) / 상업(헤저) / 비보고(소액) 순포지션을 자산별로
 * 보여 줍니다. CFTC는 <b>주 1회(화요일 기준, 금요일 발표)</b>라 항상 며칠 지난
 * 데이터입니다. 그 지연 일수를 함께 표시해야 오해가 없습니다.
 */
@Service
public class CotService {

    /** 자산별 계약 코드 (수집기 indicators.COT_ASSETS와 같아야 합니다). */
    public static final Map<String, Map<String, String>> ASSETS = assets();
    private static final int WEEKS = 3 * 52 + 10;

    private final StoreReader store;

    public CotService(StoreReader store) {
        this.store = store;
    }

    public Map<String, Object> assetList() {
        List<Map<String, String>> list = new ArrayList<>();
        ASSETS.forEach((name, info) -> {
            Map<String, String> entry = new LinkedHashMap<>(info);
            entry.put("name", name);
            list.add(entry);
        });
        return Map.of("assets", list);
    }

    /** 자산 1종의 시계열 + 요약. */
    public Map<String, Object> asset(String assetName) {
        Map<String, String> info = ASSETS.get(assetName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asset", assetName);

        if (info == null) {
            out.put("available", false);
            out.put("message", "알 수 없는 자산입니다: " + assetName);
            return out;
        }
        out.put("code", info.get("code"));
        out.put("category", info.get("category"));

        Optional<Snapshot> snapshot = store.read(
                Datasets.cotContract(info.get("code"), WEEKS),
                Datasets.MAX_AGE_SLOW, "cot_history");

        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            out.put("available", false);
            out.put("rows", List.of());
            out.put("message", "COT 저장본이 없습니다. 수집기를 실행하세요.");
            return out;
        }

        List<JsonNode> rows = Json.array(snapshot.get().payload(), "rows");
        out.put("available", !rows.isEmpty());
        snapshot.get().putFreshness(out);
        out.put("rows", rows);
        out.put("summary", summarize(assetName, rows));
        return out;
    }

    /** 전체 자산 요약 (AI 리포트·개요용). */
    public Map<String, Object> overview() {
        Optional<Snapshot> snapshot = store.read(
                Datasets.SNAP_COT_HISTORY, Datasets.MAX_AGE_SLOW, "cot_history");

        Map<String, Object> out = new LinkedHashMap<>();
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            out.put("available", false);
            out.put("assets", List.of());
            return out;
        }

        JsonNode assets = Json.child(snapshot.get().payload(), "assets");
        List<Map<String, Object>> summaries = new ArrayList<>();

        if (assets != null) {
            assets.fields().forEachRemaining(entry -> {
                List<JsonNode> rows = Json.array(entry.getValue(), "rows");
                Map<String, Object> summary = summarize(entry.getKey(), rows);
                summary.put("error", Json.asText(entry.getValue(), "error"));
                summary.put("category", Json.asText(entry.getValue(), "category"));
                summaries.add(summary);
            });
        }

        out.put("available", !summaries.isEmpty());
        snapshot.get().putFreshness(out);
        out.put("assets", summaries);
        return out;
    }

    /**
     * 자산 1종 요약: 최신 순포지션, 1/4/13주 변화, 3년 표본 내 백분위, 공시 지연 일수.
     */
    Map<String, Object> summarize(String assetName, List<JsonNode> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asset", assetName);

        if (rows == null || rows.isEmpty()) {
            out.put("available", false);
            return out;
        }

        JsonNode latest = rows.get(rows.size() - 1);
        List<Double> ncNet = new ArrayList<>();
        for (JsonNode row : rows) {
            Double value = Json.asDouble(row, "ncNet");
            if (value != null) {
                ncNet.add(value);
            }
        }

        out.put("available", true);
        out.put("date", Json.asText(latest, "date"));
        out.put("ncNet", Json.asDouble(latest, "ncNet"));
        out.put("commNet", Json.asDouble(latest, "commNet"));
        out.put("nrNet", Json.asDouble(latest, "nrNet"));
        out.put("change1w", changeOver(rows, 1));
        out.put("change4w", changeOver(rows, 4));
        out.put("change13w", changeOver(rows, 13));
        out.put("percentile", SeriesMath.percentile(ncNet));

        LocalDate reportDate = Json.parseDate(Json.asText(latest, "date"));
        out.put("ageDays", reportDate == null
                ? null : ChronoUnit.DAYS.between(reportDate, LocalDate.now()));
        return out;
    }

    private Double changeOver(List<JsonNode> rows, int weeks) {
        if (rows.size() <= weeks) {
            return null;
        }
        Double current = Json.asDouble(rows.get(rows.size() - 1), "ncNet");
        Double past = Json.asDouble(rows.get(rows.size() - 1 - weeks), "ncNet");
        return SeriesMath.difference(current, past);
    }

    private static Map<String, Map<String, String>> assets() {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        map.put("S&P 500 E-Mini", Map.of("code", "13874A", "category", "주식"));
        map.put("NASDAQ 100 E-Mini", Map.of("code", "209742", "category", "주식"));
        map.put("미국 국채 10년물", Map.of("code", "043602", "category", "채권"));
        map.put("달러 인덱스", Map.of("code", "098662", "category", "통화"));
        map.put("WTI 원유", Map.of("code", "067651", "category", "원자재"));
        map.put("금", Map.of("code", "088691", "category", "원자재"));
        return Map.copyOf(map);
    }
}
