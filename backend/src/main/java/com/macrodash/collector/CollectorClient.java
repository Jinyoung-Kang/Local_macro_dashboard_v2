package com.macrodash.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 수집기(FastAPI) 호출 클라이언트.
 *
 * <p>백엔드는 스스로 외부 시장 데이터를 긁지 않습니다. 키 관리와 파싱을 한 곳에
 * 모아 두면 "진단은 통과하는데 화면은 비는" 어긋남이 생기지 않습니다.
 *
 * <p>수집기가 죽어 있어도 백엔드는 살아 있어야 합니다. 모든 호출은 실패를
 * {@link Optional#empty()}로 돌려주고, 호출부는 저장본으로 화면을 그립니다.
 */
@Component
public class CollectorClient {

    private static final Logger log = LoggerFactory.getLogger(CollectorClient.class);

    private final RestClient client;
    private final AppProperties properties;

    public CollectorClient(AppProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(properties.getCollectorTimeoutSeconds()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getCollectorUrl())
                .requestFactory(factory);

        if (!properties.getCollectorToken().isBlank()) {
            builder = builder.defaultHeader("X-Service-Token", properties.getCollectorToken());
        }
        this.client = builder.build();
    }

    /** 태스크 1건을 지금 실행합니다 (auto 모드에서 저장본이 오래됐을 때). */
    public Optional<JsonNode> runTask(String taskName) {
        return post("/collect/task/" + taskName, Map.of());
    }

    /** 작업군 전체 실행 (수동 새로고침 버튼 등). */
    public Optional<JsonNode> runGroup(String group, boolean wait) {
        String uri = UriComponentsBuilder.fromPath("/collect")
                .queryParam("group", group)
                .queryParam("wait", wait)
                .toUriString();
        return post(uri, Map.of());
    }

    public Optional<JsonNode> requestRefresh() {
        return post("/refresh", Map.of());
    }

    public Optional<JsonNode> status() {
        return get("/status");
    }

    public Optional<JsonNode> tasks() {
        return get("/tasks");
    }

    public Optional<JsonNode> taskHistory(String task, int limit) {
        String uri = UriComponentsBuilder.fromPath("/task-history")
                .queryParam("limit", limit)
                .queryParamIfPresent("task", Optional.ofNullable(task))
                .toUriString();
        return get(uri);
    }

    public Optional<JsonNode> diagnostics() {
        return get("/diagnostics/connections");
    }

    public Optional<JsonNode> tossDiagnostics() {
        return get("/diagnostics/toss");
    }

    public Optional<JsonNode> tossExchangeRate(String base, String quote) {
        return get(UriComponentsBuilder.fromPath("/toss/exchange-rate")
                .queryParam("base", base)
                .queryParam("quote", quote)
                .toUriString());
    }

    public Optional<JsonNode> tossIndices(String symbols) {
        return get(UriComponentsBuilder.fromPath("/toss/indices")
                .queryParam("symbols", symbols)
                .toUriString());
    }

    public Optional<JsonNode> verificationReadings(String market, String investor, String tradeType) {
        return get(UriComponentsBuilder.fromPath("/verify/readings")
                .queryParam("market", market)
                .queryParam("investor", investor)
                .queryParam("tradeType", tradeType)
                .toUriString());
    }

    public Optional<JsonNode> liveRadar(String market, String investor, String tradeType,
                                        int topN, String intervalType, String targetDate) {
        return get(UriComponentsBuilder.fromPath("/live/radar")
                .queryParam("market", market)
                .queryParam("investor", investor)
                .queryParam("tradeType", tradeType)
                .queryParam("topN", topN)
                .queryParam("intervalType", intervalType)
                .queryParamIfPresent("targetDate", Optional.ofNullable(targetDate))
                .toUriString());
    }

    public Optional<JsonNode> liveTicker(String symbol, String period) {
        return get(UriComponentsBuilder.fromPath("/live/ticker/" + symbol)
                .queryParam("period", period)
                .toUriString());
    }

    public Optional<JsonNode> daumIntraday(int minutes) {
        return get(UriComponentsBuilder.fromPath("/live/daum-intraday")
                .queryParam("minutes", minutes)
                .toUriString());
    }

    private Optional<JsonNode> get(String uri) {
        try {
            return Optional.ofNullable(client.get().uri(uri).retrieve().body(JsonNode.class));
        } catch (Exception e) {
            log.warn("수집기 호출 실패 (GET {}): {}", uri, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> post(String uri, Object body) {
        try {
            return Optional.ofNullable(
                    client.post().uri(uri).body(body).retrieve().body(JsonNode.class));
        } catch (Exception e) {
            log.warn("수집기 호출 실패 (POST {}): {}", uri, e.getMessage());
            return Optional.empty();
        }
    }
}
