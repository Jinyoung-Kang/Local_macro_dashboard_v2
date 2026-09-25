package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.analytics.Json;
import com.macrodash.analytics.KrFundamentals;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 📑 국내 종목 재무 (DART 사업보고서) — 수급 레이더 종목 옆에 붙이는 안정성·성장성.
 *
 * <p>수급은 "누가 샀나"만 말합니다. 같은 순매수라도 부채비율이 높거나 이익이
 * 줄고 있는 회사라면 해석이 달라지므로, 공시 재무를 함께 봅니다.
 */
@Service
public class KrFundamentalsService {

    /** 한 요청에 받을 최대 종목 수. 화면 랭킹은 30개입니다. */
    static final int MAX_CODES = 60;

    private static final Pattern STOCK_CODE = Pattern.compile("^\\d{6}$");
    private static final String VIEWER = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

    private final StoreReader store;

    public KrFundamentalsService(StoreReader store) {
        this.store = store;
    }

    /**
     * 종목별 재무 지표.
     *
     * @param codes 쉼표로 구분한 6자리 종목코드. 형식이 틀린 코드는 무시하고, 최대 {@value #MAX_CODES}개
     * @return {@code companies: [{code, available, name, bsnsYear, fsDiv, dartUrl, metrics...}]}
     *         + 신선도. 저장본에 없는 종목은 {@code available=false} — ETF·ETN·스팩 등은 DART
     *         재무 대상이 아니거나 아직 수집 전입니다.
     */
    public Map<String, Object> fundamentals(String codes) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> requested = parseCodes(codes);
        out.put("requested", requested.size());

        Optional<Snapshot> snapshot = store.read(
                Datasets.SNAP_DART_FUNDAMENTALS, Datasets.MAX_AGE_WEEKLY, "dart_fundamentals");
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            out.put("available", false);
            out.put("companies", List.of());
            out.put("message", "DART 재무 저장본이 없습니다. DART_API_KEY를 설정하고 "
                    + "'dart_fundamentals' 수집을 실행하세요.");
            return out;
        }
        snapshot.get().putFreshness(out);
        out.put("available", true);
        out.put("source", Json.asText(snapshot.get().payload(), "source"));

        JsonNode stored = Json.child(snapshot.get().payload(), "companies");
        List<Map<String, Object>> companies = new ArrayList<>();
        for (String code : requested) {
            JsonNode company = stored == null ? null : stored.get(code);
            companies.add(company == null ? Map.of("code", code, "available", false) : describe(code, company));
        }
        out.put("companies", companies);
        return out;
    }

    static Map<String, Object> describe(String code, JsonNode company) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("code", code);
        one.put("available", true);
        one.put("name", Json.asText(company, "name"));
        one.put("bsnsYear", Json.asText(company, "bsnsYear"));
        one.put("fsDiv", Json.asText(company, "fsDiv"));
        one.put("fsLabel", "CFS".equals(Json.asText(company, "fsDiv")) ? "연결" : "별도");
        String receipt = Json.asText(company, "rceptNo");
        // 공시 원문으로 바로 가는 링크. 숫자가 이상해 보이면 원문을 확인할 수 있어야 합니다.
        one.put("dartUrl", receipt != null && receipt.matches("\\d{14}") ? VIEWER + receipt : null);
        one.putAll(KrFundamentals.metrics(accounts(Json.child(company, "accounts"))));
        return one;
    }

    static Map<String, KrFundamentals.Amounts> accounts(JsonNode node) {
        Map<String, KrFundamentals.Amounts> out = new LinkedHashMap<>();
        if (node == null) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            out.put(entry.getKey(), new KrFundamentals.Amounts(
                    Json.asDouble(entry.getValue(), "current"),
                    Json.asDouble(entry.getValue(), "previous")));
        }
        return out;
    }

    static Set<String> parseCodes(String codes) {
        Set<String> out = new LinkedHashSet<>();
        if (codes == null) {
            return out;
        }
        for (String raw : codes.split(",")) {
            String code = raw.trim();
            if (STOCK_CODE.matcher(code).matches()) {
                out.add(code);
            }
            if (out.size() >= MAX_CODES) {
                break;
            }
        }
        return out;
    }
}
