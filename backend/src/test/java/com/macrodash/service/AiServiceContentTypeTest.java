package com.macrodash.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 응답을 Content-Type에 의존하지 않고 읽는지 확인합니다.
 *
 * <p>실제로 화면에 떴던 오류입니다.
 * <pre>
 * 생성 실패: Error while extracting response for type
 *   [com.fasterxml.jackson.databind.JsonNode]
 *   and content type [application/octet-stream]
 * </pre>
 *
 * <p>NVIDIA는 같은 엔드포인트인데도 모델에 따라 {@code application/octet-stream}을
 * 붙여 보내는 경우가 있습니다. 본문은 멀쩡한 JSON인데 Jackson 컨버터가
 * {@code application/json} 계열만 처리해서 거절하던 것이었습니다.
 */
class AiServiceContentTypeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String contentType, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    private static final String OPENAI_BODY = """
            {"choices":[{"message":{"content":"분석 결과입니다."}}]}
            """;

    @Test
    @DisplayName("application/octet-stream으로 와도 JSON으로 읽는다")
    void readsJsonDespiteOctetStreamContentType() throws Exception {
        String endpoint = startServer("application/octet-stream", OPENAI_BODY);

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).as("octet-stream이어도 성공해야 합니다").isEqualTo(true);
        assertThat(result.get("response")).isEqualTo("분석 결과입니다.");
    }

    @Test
    @DisplayName("정상 application/json도 그대로 동작한다")
    void stillReadsPlainJson() throws Exception {
        String endpoint = startServer("application/json", OPENAI_BODY);

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(true);
        assertThat(result.get("response")).isEqualTo("분석 결과입니다.");
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 실패로 보고한다 — 성공으로 위장하지 않는다")
    void reportsFailureOnNonJsonBody() throws Exception {
        String endpoint = startServer("text/html", "<html>502 Bad Gateway</html>");

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get("error"))).isNotBlank();
    }

    @Test
    @DisplayName("빈 본문도 실패로 보고한다")
    void reportsFailureOnEmptyBody() throws Exception {
        String endpoint = startServer("application/octet-stream", "");

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
    }

    @Test
    @DisplayName("키가 없으면 호출하지 않고 이유를 남긴다")
    void missingKeyIsReportedNotSilentlySkipped() {
        AtomicReference<Map<String, Object>> result = new AtomicReference<>(
                new AiService().callOpenAiFormat(
                        "테스트", "http://127.0.0.1:1/chat", "", "test-model", "질문", null));

        assertThat(result.get().get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get().get("error"))).contains("API Key 누락");
    }
}
