package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.analytics.Json;
import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import com.macrodash.store.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 📡 외국인/기관 수급 레이더.
 *
 * <p>수집 순서는 수집기가 관리합니다(KIS → Daum → Naver → LS → PyKrx → 누적 이력).
 * 백엔드는 저장본을 읽고, 필요하면 수집을 요청하며, <b>어느 출처가 실제로
 * 성공했는지</b>와 <b>이력 대체 여부</b>를 화면에 그대로 전달합니다.
 *
 * <p>이력 대체(isHistorical=true)는 "지금 시점의 수급이 아니다"라는 뜻이라
 * 화면이 반드시 날짜와 함께 경고해야 합니다.
 */
@Service
public class RadarService {

    public static final List<String> MARKETS = List.of("KOSPI", "KOSDAQ");
    public static final List<String> INVESTORS =
            List.of("외국인", "기관", "개인", "연기금", "금융투자", "투신");
    public static final List<String> TRADE_TYPES = List.of("순매수", "순매도");
    public static final List<String> INTERVALS = List.of("TODAY", "DAYS_5", "DAYS_20");

    private final StoreReader store;
    private final StoreRepository repository;
    private final CollectorClient collector;

    public RadarService(StoreReader store, StoreRepository repository, CollectorClient collector) {
        this.store = store;
        this.repository = repository;
        this.collector = collector;
    }

    public Map<String, Object> ranking(String market, String investor, String tradeType,
                                       int topN, String intervalType, String targetDate) {
        String snapshotName = Datasets.radarScanner(market, investor, tradeType, intervalType);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("investor", investor);
        out.put("tradeType", tradeType);
        out.put("intervalType", intervalType);
        out.put("readMode", store.readMode().name().toLowerCase());

        // 과거 날짜나 기본 조합이 아닌 조회는 저장본이 없을 수 있습니다.
        // store_only 모드가 아니면 수집기에 직접 물어봅니다.
        boolean custom = targetDate != null
                || !MARKETS.get(0).equals(market)
                || topN != 30;

        Optional<Snapshot> snapshot = custom
                ? Optional.empty()
                : store.readStored(snapshotName);

        boolean fresh = snapshot.isPresent()
                && snapshot.get().isFresh(Datasets.MAX_AGE_REALTIME);

        if (fresh || store.readMode() == AppProperties.ReadMode.STORE_ONLY) {
            if (snapshot.isPresent() && snapshot.get().payload() != null) {
                return fillFromSnapshot(out, snapshot.get());
            }
            if (store.readMode() == AppProperties.ReadMode.STORE_ONLY) {
                out.put("available", false);
                out.put("rows", List.of());
                out.put("message",
                        "store_only 모드입니다. 저장본이 없어 표시할 수급이 없습니다 "
                                + "(수집은 수집기가 담당합니다).");
                return out;
            }
        }

        Optional<JsonNode> live = collector.liveRadar(
                market, investor, tradeType, topN, intervalType, targetDate);

        if (live.isPresent()) {
            JsonNode payload = live.get();
            out.put("available", !Json.array(payload, "rows").isEmpty());
            out.put("source", Json.asText(payload, "source"));
            out.put("sourceKind", Json.asText(payload, "sourceKind"));
            out.put("isHistorical", Json.asBoolean(payload, "isHistorical"));
            out.put("historyDate", Json.asText(payload, "historyDate"));
            out.put("rows", payload.get("rows"));
            if (Json.asBoolean(payload, "isHistorical")) {
                out.put("warning", historicalWarning(Json.asText(payload, "historyDate")));
            }
            return out;
        }

        if (snapshot.isPresent() && snapshot.get().payload() != null) {
            // 수집 실패 → 오래된 저장본이라도 보여 줍니다(화면이 비는 것보다 낫습니다).
            Map<String, Object> fallback = fillFromSnapshot(out, snapshot.get());
            fallback.put("warning",
                    "수집에 실패해 저장본을 표시합니다 (수집 시각 "
                            + snapshot.get().collectedAtKst() + ").");
            return fallback;
        }

        out.put("available", false);
        out.put("rows", List.of());
        out.put("message", "수급 데이터를 얻지 못했습니다. 수집기 상태를 확인하세요.");
        return out;
    }

    private Map<String, Object> fillFromSnapshot(Map<String, Object> out, Snapshot snapshot) {
        JsonNode payload = snapshot.payload();
        out.put("available", !Json.array(payload, "rows").isEmpty());
        snapshot.putFreshness(out);
        out.put("stale", !snapshot.isFresh(Datasets.MAX_AGE_REALTIME));
        out.put("source", Json.asText(payload, "source"));
        out.put("sourceKind", Json.asText(payload, "sourceKind"));
        out.put("isHistorical", Json.asBoolean(payload, "isHistorical"));
        out.put("historyDate", Json.asText(payload, "historyDate"));
        out.put("rows", payload.get("rows"));
        if (Json.asBoolean(payload, "isHistorical")) {
            out.put("warning", historicalWarning(Json.asText(payload, "historyDate")));
        }
        return out;
    }

    private String historicalWarning(String historyDate) {
        return "외부 데이터 소스가 모두 실패해 수집기가 저장해 둔 이력("
                + (historyDate == null ? "날짜 미상" : historyDate)
                + ")을 보여 주고 있습니다. 지금 시점의 수급이 아닙니다.";
    }

    /** 누적 이력 조회 (Naver/Daum이 제공하지 않는 과거 데이터). */
    public Map<String, Object> history(String market, String investor, String tradeType,
                                       String obsDate, String startDate) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (market != null) {
            filters.put("market", market);
        }
        if (investor != null) {
            filters.put("investor", investor);
        }
        if (tradeType != null) {
            filters.put("tradeType", tradeType);
        }

        List<JsonNode> rows = repository.readObservations(
                Datasets.OBS_RADAR,
                obsDate == null ? null : LocalDate.parse(obsDate),
                startDate == null ? null : LocalDate.parse(startDate),
                filters);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", repository.listObservationDates(Datasets.OBS_RADAR));
        out.put("rows", rows);
        out.put("note",
                "Naver·Daum·KRX는 과거 날짜 조회를 지원하지 않습니다. 이 이력은 수집기가 "
                        + "돌 때마다 쌓아 온 값이라 외부에서 다시 받을 수 없습니다 — "
                        + "데이터베이스를 백업할 가치가 있습니다.");
        return out;
    }

    /** 5개 데이터 소스 연결 진단. */
    public Map<String, Object> diagnostics() {
        Optional<JsonNode> payload = collector.diagnostics();
        if (payload.isEmpty()) {
            return Map.of(
                    "available", false,
                    "message", "수집기에 연결하지 못했습니다. 수집기가 실행 중인지 확인하세요.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        payload.get().fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }
}
