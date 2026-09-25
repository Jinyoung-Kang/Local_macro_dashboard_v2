package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.Kst;
import com.macrodash.analytics.HousingStats;
import com.macrodash.analytics.Json;
import com.macrodash.store.Datasets;
import com.macrodash.store.StoreRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 🏠 서울 아파트 매매 — 월별 거래량·평당가 중위값, 구별 비교.
 *
 * <p>거래량은 가격보다 먼저 움직이는 경향이 있어 부동산 경기의 선행 신호로 봅니다.
 * 금리·유동성 화면과 나란히 보라는 뜻에서 매크로 대시보드에 둡니다.
 *
 * <p>주의사항 — 실거래 신고 기한이 계약 후 30일이라 최근 두 달은 거래가 덜 잡혀
 * 있습니다({@code provisional=true}). 거래량이 "급감"한 것처럼 보이는 착시를 막기 위해
 * 화면이 이를 표시해야 합니다.
 */
@Service
public class HousingService {

    /** 신고 기한(30일) 때문에 아직 덜 채워진 최근 개월 수. 수집기의 APT_REFRESH_MONTHS와 같습니다. */
    static final int PROVISIONAL_MONTHS = 2;
    static final int SEOUL_GU_COUNT = 25;

    private final StoreRepository repository;

    public HousingService(StoreRepository repository) {
        this.repository = repository;
    }

    /**
     * @return {@code months: [{month, count, medianPricePerPyeong, medianAmount, coverage, provisional}]}
     *         (서울 전체, 오래된 순), {@code districts: [...]} (기준월의 구별 값과 전년 동월 대비),
     *         {@code referenceMonth} (잠정이 아닌 가장 최근 달)
     */
    public Map<String, Object> seoul() {
        LocalDate thisMonth = Kst.today().withDayOfMonth(1);
        // 기준월(두 달 전)의 전년 동월까지 읽어야 전년 대비를 낼 수 있습니다 (수집기 APT_MONTHS=15).
        LocalDate from = thisMonth.minusMonths(14);
        List<JsonNode> rows = repository.readObservations(Datasets.OBS_MOLIT_APT, null, from, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "국토교통부 아파트 매매 실거래가 (해제 거래 제외)");
        if (rows.isEmpty()) {
            out.put("available", false);
            out.put("message", "실거래 저장본이 없습니다. DATA_GO_KR_SERVICE_KEY를 설정하고 "
                    + "국토교통부_아파트 매매 실거래가 자료 활용신청을 확인하세요.");
            out.put("months", List.of());
            out.put("districts", List.of());
            return out;
        }

        // 월 → (구 코드 → 거래), 구 이름
        TreeMap<String, Map<String, List<HousingStats.Trade>>> byMonth = new TreeMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String month = String.valueOf(Json.asText(row, "obsDate"));
            String lawd = Json.asText(row, "lawd");
            if (lawd == null) {
                continue;
            }
            names.putIfAbsent(lawd, Json.asText(row, "name"));
            byMonth.computeIfAbsent(month, key -> new LinkedHashMap<>()).put(lawd, trades(row));
        }

        LocalDate provisionalFrom = thisMonth.minusMonths(PROVISIONAL_MONTHS - 1);
        List<Map<String, Object>> months = new ArrayList<>();
        String reference = null;
        for (Map.Entry<String, Map<String, List<HousingStats.Trade>>> entry : byMonth.entrySet()) {
            List<HousingStats.Trade> pooled = new ArrayList<>();
            entry.getValue().values().forEach(pooled::addAll);
            boolean provisional = !LocalDate.parse(entry.getKey()).isBefore(provisionalFrom);
            int coverage = entry.getValue().size();

            Map<String, Object> one = new LinkedHashMap<>();
            one.put("month", entry.getKey().substring(0, 7));
            one.put("count", pooled.size());
            one.put("medianPricePerPyeong", HousingStats.medianPricePerPyeong(pooled));
            one.put("medianAmount", HousingStats.medianAmount(pooled));
            one.put("coverage", coverage);
            one.put("provisional", provisional);
            months.add(one);
            if (!provisional && coverage == SEOUL_GU_COUNT) {
                reference = entry.getKey();
            }
        }
        out.put("available", true);
        out.put("months", months);
        out.put("referenceMonth", reference == null ? null : reference.substring(0, 7));
        out.put("districts", reference == null ? List.of() : districts(byMonth, reference, names));
        out.put("note", "최근 " + PROVISIONAL_MONTHS + "개월은 신고 기한(계약 후 30일) 때문에 거래가 덜 잡힌 잠정치입니다. "
                + "구가 25개 모두 모이지 않은 달은 서울 전체 값이 일부 구만 반영합니다(coverage).");
        return out;
    }

    private static List<Map<String, Object>> districts(TreeMap<String, Map<String, List<HousingStats.Trade>>> byMonth,
                                                       String reference, Map<String, String> names) {
        String yearAgo = LocalDate.parse(reference).minusYears(1).toString();
        Map<String, List<HousingStats.Trade>> now = byMonth.get(reference);
        Map<String, List<HousingStats.Trade>> before = byMonth.getOrDefault(yearAgo, Map.of());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<HousingStats.Trade>> entry : now.entrySet()) {
            Double price = HousingStats.medianPricePerPyeong(entry.getValue());
            List<HousingStats.Trade> previous = before.get(entry.getKey());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("lawd", entry.getKey());
            one.put("name", names.get(entry.getKey()));
            one.put("count", entry.getValue().size());
            one.put("medianPricePerPyeong", price);
            one.put("medianAmount", HousingStats.medianAmount(entry.getValue()));
            one.put("countYearAgo", previous == null ? null : previous.size());
            one.put("priceYoyPct", previous == null ? null
                    : HousingStats.yoyPct(price, HousingStats.medianPricePerPyeong(previous)));
            out.add(one);
        }
        out.sort((a, b) -> Double.compare(
                b.get("medianPricePerPyeong") instanceof Double d ? d : -1,
                a.get("medianPricePerPyeong") instanceof Double d ? d : -1));
        return out;
    }

    private static List<HousingStats.Trade> trades(JsonNode row) {
        List<HousingStats.Trade> out = new ArrayList<>();
        for (JsonNode pair : Json.array(row, "trades")) {
            if (pair.isArray() && pair.size() == 2 && pair.get(0).isNumber() && pair.get(1).isNumber()) {
                out.add(new HousingStats.Trade(pair.get(0).asDouble(), pair.get(1).asDouble()));
            }
        }
        return out;
    }
}
