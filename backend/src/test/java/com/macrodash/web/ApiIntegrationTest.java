package com.macrodash.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.store.Datasets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 통합 테스트 (실제 PostgreSQL 필요).
 *
 * <p>{@code TEST_DATABASE_URL}이 없으면 Spring이 데이터소스를 만들지 못해
 * 컨텍스트 기동이 실패하므로, CI에서는 서비스 컨테이너로 DB를 띄웁니다.
 * 로컬에서 DB 없이 돌릴 때는 {@code -Dtest=!ApiIntegrationTest}로 제외하세요.
 *
 * <p>확인하는 것
 * <ul>
 *   <li>인증 없이는 데이터 API에 접근할 수 없다</li>
 *   <li>로그인 후에는 저장본을 읽어 화면 계약대로 응답한다</li>
 *   <li>저장본이 없어도 500이 아니라 "available=false"로 답한다</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/macrodash}",
        "spring.cache.type=none",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "dashboard.password=test-password",
        "dashboard.jwt-secret=integration-test-secret-key-32-bytes!",
        "dashboard.read-mode=store_only",
        "dashboard.collector-url=http://localhost:1"     // 수집기가 없어도 화면은 떠야 합니다
})
class ApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private String sessionCookie;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM snapshots");
        sessionCookie = login("test-password");
    }

    @Test
    @DisplayName("인증 없이는 데이터 API에 접근할 수 없다")
    void requiresAuthentication() {
        ResponseEntity<String> response = rest.getForEntity(
                url("/api/macro/overview"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("잘못된 비밀번호는 거부된다")
    void rejectsWrongPassword() {
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"), Map.of("password", "wrong"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("저장본이 없어도 500이 아니라 available=false로 답한다")
    void emptyStoreDoesNotBreakScreens() {
        for (String path : List.of(
                "/api/macro/overview", "/api/liquidity", "/api/sector/rotation",
                "/api/krx/futures", "/api/cot/overview")) {
            JsonNode body = authorizedGet(path);
            assertThat(body.path("available").asBoolean(true))
                    .as("%s는 저장본이 없을 때 available=false여야 합니다", path)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("매크로 저장본을 그대로 화면 계약으로 전달한다")
    void servesStoredMacroSnapshot() {
        insertSnapshot(Datasets.SNAP_MACRO_COLLECTED, """
                {
                  "categories": [{
                    "id": "fx", "title": "💵 통화 및 환율", "note": "실시간",
                    "items": [{
                      "key": "usdkrw", "name": "원/달러 (USD/KRW)", "status": "ok",
                      "price": 1389.5, "priceStr": "1,389.50",
                      "delta": -3.2, "pct": -0.23, "deltaStr": "-3.20 (-0.23%)",
                      "prevStr": "1,392.70", "prevValue": 1392.7,
                      "lastTs": "15:30:00 KST"
                    }]
                  }],
                  "rates": {
                    "us02y": {"current": 3.62, "previous": 3.58},
                    "us10y": {"current": 4.11, "previous": 4.05}
                  }
                }
                """);

        JsonNode body = authorizedGet("/api/macro/overview");

        assertThat(body.path("available").asBoolean()).isTrue();
        assertThat(body.path("categories").get(0).path("items").get(0).path("priceStr").asText())
                .isEqualTo("1,389.50");
        // 10Y − 2Y = 4.11 − 3.62 = 0.49
        assertThat(body.path("spreads").path("realtime").path("spread").asDouble())
                .isEqualTo(0.49, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("추정치 KRX 저장본은 isEstimated로 표시된다")
    void estimatedKrxSnapshotIsFlagged() {
        insertSnapshot(Datasets.SNAP_KRX_FUTURES, """
                {
                  "isEstimated": true,
                  "rows": [{
                    "date": "2026-09-11", "futuresClose": 1088.3, "changePct": -2.13,
                    "changePctReported": null, "volume": null, "openInterest": null,
                    "oiChange": null, "theoryPrice": null, "marketBasis": null,
                    "contractName": "KOSPI 200 최근월물 (KODEX 200 기반 추정)",
                    "marketPhase": "판정 불가 (등락률 미제공)", "cotOiIndex": null
                  }]
                }
                """, "estimated");

        JsonNode body = authorizedGet("/api/krx/futures");

        assertThat(body.path("isEstimated").asBoolean()).isTrue();
        assertThat(body.path("estimateNotice").asText()).contains("추정치");
        assertThat(body.path("latest").path("marketPhase").asText()).isEqualTo("판정 불가 (등락률 미제공)");
        // 베이시스를 모르면 0으로 메우지 않고 "데이터 미제공"으로 표시합니다.
        assertThat(body.path("latest").path("basisState").asText()).isEqualTo("데이터 미제공");
    }

    @Test
    @DisplayName("store_only 모드에서는 수집기가 죽어 있어도 응답한다")
    void storeOnlyModeNeverWaitsForCollector() {
        JsonNode body = authorizedGet("/api/status");

        assertThat(body.path("readMode").asText()).isEqualTo("store_only");
        assertThat(body.path("collectorReachable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("로그아웃하면 세션이 무효가 된다")
    void logoutClearsSession() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);

        rest.exchange(url("/api/auth/logout"), HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);

        ResponseEntity<String> after = rest.exchange(
                url("/api/auth/session"), HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);

        assertThat(after.getBody()).contains("authenticated");
    }

    // ------------------------------------------------------------------ helpers
    private String login(String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(Map.of("password", password), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies.get(0).split(";")[0];
    }

    @Test
    @DisplayName("13F: quarters가 0이나 음수여도 500이 아니다")
    void sec13fSurvivesOutOfRangeQuarters() {
        // quarters는 URL 파라미터입니다. 예전에는 그대로 subList(0, quarters)에
        // 넘겨서 quarters=0이면 빈 목록의 get(0)으로, quarters=-1이면 subList가
        // 곧바로 예외를 던져 500이 났습니다.
        String cik = "0001067983";      // 버크셔
        insertSnapshot(
                Datasets.sec13f(cik, Datasets.MAX_TRACKED_QUARTERS),
                """
                {"quarters":[
                  {"filingDate":"2026-08-14","reportDate":"2026-06-30","totalValue":1000,
                   "holdings":[{"name":"APPLE INC","value":600,"weight":60.0},
                               {"name":"COCA COLA CO","value":400,"weight":40.0}]},
                  {"filingDate":"2026-05-15","reportDate":"2026-03-31","totalValue":900,
                   "holdings":[{"name":"APPLE INC","value":500,"weight":55.6},
                               {"name":"COCA COLA CO","value":400,"weight":44.4}]}
                ]}
                """);

        for (int quarters : new int[]{0, -1, 1, 8, 999}) {
            JsonNode body = authorizedGet(
                    "/api/sec13f/portfolio?cik=" + cik + "&quarters=" + quarters);
            assertThat(body.path("available").asBoolean())
                    .as("quarters=%d 에서도 응답이 나와야 합니다", quarters)
                    .isTrue();
            assertThat(body.path("quarters").size())
                    .as("quarters=%d 에서 최소 한 분기는 나와야 합니다", quarters)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    private JsonNode authorizedGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);

        ResponseEntity<JsonNode> response = rest.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(null, headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("%s 응답 상태", path)
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void insertSnapshot(String name, String payload) {
        insertSnapshot(name, payload, "ok");
    }

    private void insertSnapshot(String name, String payload, String status) {
        jdbc.update(
                "INSERT INTO snapshots (name, payload, kind, status, collected_at) "
                        + "VALUES (?, ?::jsonb, 'json', ?, now()) "
                        + "ON CONFLICT (name) DO UPDATE SET payload = EXCLUDED.payload, "
                        + "status = EXCLUDED.status, collected_at = EXCLUDED.collected_at",
                name, payload, status);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
