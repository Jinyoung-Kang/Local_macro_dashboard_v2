package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🤖 AI 엔진 라우팅 (NVIDIA / Cerebras / Cloudflare).
 *
 * <p>구버전 services/ai_service.py의 구조를 유지합니다.
 * <ul>
 *   <li>엔진 레지스트리 + 자동 폴오버(한 엔진이 실패하면 다음 엔진)</li>
 *   <li>응답이 한국어가 아니면 번역 전용 모델로 한 번 더 호출</li>
 *   <li>키가 없는 엔진은 <b>조용히 건너뛰지 않고</b> 이유를 남깁니다</li>
 * </ul>
 *
 * <p>AI는 <b>수집된 데이터만</b> 근거로 삼습니다. 리포트 프롬프트에는 대시보드
 * 원본 텍스트가 그대로 들어가고, 추정치·대용 지표에는 경고 문구가 함께
 * 들어갑니다({@link SnapshotTextService}).
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final String NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String CEREBRAS_URL = "https://api.cerebras.ai/v1/chat/completions";
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern ALPHABETIC = Pattern.compile("[A-Za-z가-힣]");

    public record Engine(String id, String label, String provider, String model, String description) {
    }

    /** 분석 엔진 레지스트리 (번역 전용 모델은 선택지에서 제외). */
    public static final List<Engine> ENGINES = List.of(
            new Engine("auto", "⚡ 자동 탐색 — 권장 (Failover)", "auto", null,
                    "사용 가능한 엔진을 우선순위대로 자동 호출합니다."),
            new Engine("nvidia_nemotron", "🟢 NVIDIA — Nemotron-3 Super 120B", "nvidia",
                    "nvidia/nemotron-3-super-120b-a12b", "장문 투자 분석 및 구조화된 리포트"),
            new Engine("nvidia_gpt_oss_120b", "🟢 NVIDIA — OpenAI GPT-OSS 120B", "nvidia",
                    "openai/gpt-oss-120b", "고난도 추론·장문 종합 분석"),
            new Engine("nvidia_gpt_oss_20b", "🟢 NVIDIA — OpenAI GPT-OSS 20B", "nvidia",
                    "openai/gpt-oss-20b", "비교적 빠른 보조 분석"),
            new Engine("nvidia_llama_33_70b", "🟢 NVIDIA — Meta Llama 3.3 70B Instruct", "nvidia",
                    "meta/llama-3.3-70b-instruct", "범용 지시 이행·다국어 분석"),
            new Engine("cloudflare_deepseek", "🟠 Cloudflare — DeepSeek-R1 (32B)", "cloudflare",
                    "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b", "추론형 분석 보조"),
            new Engine("cloudflare_llama", "🟠 Cloudflare — Llama 3.3 70B FP8 Fast", "cloudflare",
                    "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "장문 매크로·투자 분석용 고속 모델"),
            new Engine("cerebras_llama", "🔵 Cerebras — Llama 3.3 70B", "cerebras",
                    "llama-3.3-70b", "초고속 장문 생성"));

    private static final List<String> FAILOVER_ORDER = List.of(
            "nvidia_nemotron", "nvidia_gpt_oss_120b", "nvidia_gpt_oss_20b",
            "cerebras_llama", "cloudflare_deepseek", "cloudflare_llama");

    /** 번역 전용 모델 (분석 선택지에서 제외). */
    private static final Map<String, String> TRANSLATORS = Map.of(
            "cloudflare", "@cf/google/gemma-4-26b-a4b-it",
            "nvidia", "google/gemma-4-31b-it");

    private static final String TRANSLATION_PROMPT = """
            당신은 금융·투자 리포트 전문 한국어 번역가입니다.

            아래 원문을 자연스럽고 정확한 한국어로 번역하십시오.

            반드시 지켜야 할 규칙:
            1. 원문의 숫자, 통화 단위, 백분율, 종목 코드, 날짜, 티커를 변경하지 마십시오.
            2. Markdown 제목, 목록, 표, 코드 블록, 굵게 표시를 유지하십시오.
            3. Markdown 표의 파이프(|), 행 구분, 줄바꿈 구조를 보존하십시오.
            4. 원문의 분석·투자 의견을 추가하거나 삭제하지 마십시오.
            5. 번역문만 출력하고, "번역:" 같은 서문은 쓰지 마십시오.
            """;

    private final RestClient http;

    @Value("${dashboard.ai.nvidia-api-key:}")
    private String nvidiaKey;

    @Value("${dashboard.ai.cerebras-api-key:}")
    private String cerebrasKey;

    @Value("${dashboard.ai.cloudflare-account-id:}")
    private String cloudflareAccountId;

    @Value("${dashboard.ai.cloudflare-api-token:}")
    private String cloudflareToken;

    public AiService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(150));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** 화면이 선택지를 그릴 때 쓰는 목록 + 키 보유 여부. */
    public Map<String, Object> engines() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Engine engine : ENGINES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", engine.id());
            entry.put("label", engine.label());
            entry.put("provider", engine.provider());
            entry.put("model", engine.model());
            entry.put("description", engine.description());
            entry.put("available", hasKey(engine.provider()));
            list.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engines", list);
        out.put("keys", Map.of(
                "nvidia", !nvidiaKey.isBlank(),
                "cerebras", !cerebrasKey.isBlank(),
                "cloudflare", !cloudflareAccountId.isBlank() && !cloudflareToken.isBlank()));
        out.put("enabled", anyKeyPresent());
        return out;
    }

    public boolean anyKeyPresent() {
        return !nvidiaKey.isBlank() || !cerebrasKey.isBlank()
                || (!cloudflareAccountId.isBlank() && !cloudflareToken.isBlank());
    }

    /** 엔진 1개 호출 (auto면 폴오버). */
    public Map<String, Object> generate(String engineId, String prompt, String systemPrompt) {
        if (!anyKeyPresent()) {
            return failure("설정 없음",
                    "AI 키가 하나도 설정되지 않았습니다. NVIDIA / Cerebras / Cloudflare 중 "
                            + "하나 이상을 설정하세요.");
        }

        if (engineId == null || engineId.isBlank() || "auto".equals(engineId)) {
            return withFailover(prompt, systemPrompt);
        }

        Engine engine = ENGINES.stream()
                .filter(e -> e.id().equals(engineId))
                .findFirst()
                .orElse(null);

        if (engine == null) {
            return failure(engineId, "지원하지 않는 AI 엔진입니다: " + engineId);
        }
        Map<String, Object> result = call(engine, prompt, systemPrompt);
        return translateIfNeeded(result, engine.provider());
    }

    /** 자동 폴오버: 앞 엔진이 실패하면 다음 엔진을 시도하고 경로를 남깁니다. */
    private Map<String, Object> withFailover(String prompt, String systemPrompt) {
        List<String> attempts = new ArrayList<>();

        for (String engineId : FAILOVER_ORDER) {
            Engine engine = ENGINES.stream()
                    .filter(e -> e.id().equals(engineId))
                    .findFirst()
                    .orElse(null);
            if (engine == null || !hasKey(engine.provider())) {
                attempts.add(engineId + ": 키 없음");
                continue;
            }

            Map<String, Object> result = call(engine, prompt, systemPrompt);
            attempts.add(engineId + ": " + (Boolean.TRUE.equals(result.get("status"))
                    ? "성공" : String.valueOf(result.get("error"))));

            if (Boolean.TRUE.equals(result.get("status"))) {
                result.put("failoverPath", attempts);
                return translateIfNeeded(result, engine.provider());
            }
        }

        Map<String, Object> failed = failure("auto", "모든 AI 엔진 호출에 실패했습니다.");
        failed.put("failoverPath", attempts);
        return failed;
    }

    private Map<String, Object> call(Engine engine, String prompt, String systemPrompt) {
        return switch (engine.provider()) {
            case "nvidia" -> callOpenAiFormat(engine.label(), NVIDIA_URL, nvidiaKey,
                    engine.model(), prompt, systemPrompt);
            case "cerebras" -> callOpenAiFormat(engine.label(), CEREBRAS_URL, cerebrasKey,
                    engine.model(), prompt, systemPrompt);
            case "cloudflare" -> callCloudflare(engine.label(), engine.model(), prompt, systemPrompt);
            default -> failure(engine.label(), "알 수 없는 제공자: " + engine.provider());
        };
    }

    private Map<String, Object> callOpenAiFormat(String label, String endpoint, String apiKey,
                                                 String model, String prompt, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            return failure(label, label + " API Key 누락");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", prompt));

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.3,
                "max_tokens", 4096);

        long started = System.currentTimeMillis();
        try {
            JsonNode response = http.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            long elapsed = System.currentTimeMillis() - started;
            String text = extractOpenAiText(response);

            if (text == null || text.isBlank()) {
                return failure(label, "응답 텍스트 추출 실패", elapsed);
            }
            return success(label, model, text.trim(), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            log.warn("AI 호출 실패 ({}): {}", label, e.getMessage());
            return failure(label, e.getMessage(), elapsed);
        }
    }

    private String extractOpenAiText(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode choices = response.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            return null;
        }
        String content = message.path("content").asText("");
        if (content.isBlank()) {
            // 추론형 모델은 reasoning_content에만 담아 주는 경우가 있습니다.
            content = message.path("reasoning_content").asText("");
        }
        return content;
    }

    private Map<String, Object> callCloudflare(String label, String model,
                                               String prompt, String systemPrompt) {
        if (cloudflareAccountId.isBlank() || cloudflareToken.isBlank()) {
            return failure(label, "Cloudflare 인증 정보 누락");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", prompt));

        long started = System.currentTimeMillis();
        try {
            JsonNode response = http.post()
                    .uri("https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
                            .formatted(cloudflareAccountId, model))
                    .header("Authorization", "Bearer " + cloudflareToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messages", messages))
                    .retrieve()
                    .body(JsonNode.class);

            long elapsed = System.currentTimeMillis() - started;
            if (response != null && response.path("success").asBoolean(false)) {
                String text = response.path("result").path("response").asText("");
                if (!text.isBlank()) {
                    return success(label, model, text.trim(), elapsed);
                }
            }
            return failure(label, "응답 실패: "
                    + (response == null ? "null" : response.path("errors").toString()), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            log.warn("Cloudflare AI 호출 실패: {}", e.getMessage());
            return failure(label, e.getMessage(), elapsed);
        }
    }

    /** 응답이 한국어가 아니면 제공자별 번역기로 한 번 더 호출합니다. */
    private Map<String, Object> translateIfNeeded(Map<String, Object> result, String provider) {
        if (!Boolean.TRUE.equals(result.get("status"))) {
            return result;
        }
        String text = String.valueOf(result.get("response"));
        if (isKorean(text)) {
            result.put("translationInfo", "번역 불필요 — 한국어 응답");
            return result;
        }

        Map<String, Object> translated = switch (provider) {
            case "nvidia" -> callOpenAiFormat("NVIDIA 번역기", NVIDIA_URL, nvidiaKey,
                    TRANSLATORS.get("nvidia"), text, TRANSLATION_PROMPT);
            default -> callCloudflare("Cloudflare 번역기", TRANSLATORS.get("cloudflare"),
                    text, TRANSLATION_PROMPT);
        };

        if (Boolean.TRUE.equals(translated.get("status"))) {
            result.put("originalResponse", text);
            result.put("response", translated.get("response"));
            result.put("translationInfo", "자동 한국어 번역 완료 — " + translated.get("provider"));
        } else {
            result.put("translationInfo", "자동 번역 실패 — 분석 원문을 그대로 표시합니다.");
            result.put("translationError", translated.get("error"));
        }
        return result;
    }

    /** 한글 비중으로 한국어 응답인지 간단히 판별합니다. */
    static boolean isKorean(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int korean = count(HANGUL, text);
        int alphabetic = Math.max(1, count(ALPHABETIC, text));
        return korean >= 8 && (double) korean / alphabetic >= 0.05;
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean hasKey(String provider) {
        return switch (provider) {
            case "nvidia" -> !nvidiaKey.isBlank();
            case "cerebras" -> !cerebrasKey.isBlank();
            case "cloudflare" -> !cloudflareAccountId.isBlank() && !cloudflareToken.isBlank();
            case "auto" -> anyKeyPresent();
            default -> false;
        };
    }

    private Map<String, Object> success(String provider, String model, String text, long elapsedMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", true);
        out.put("provider", provider);
        out.put("model", model);
        out.put("response", text);
        out.put("error", null);
        out.put("latencyMs", elapsedMs);
        out.put("pipelineStep", provider + " 성공");
        return out;
    }

    private Map<String, Object> failure(String provider, String error) {
        return failure(provider, error, 0);
    }

    private Map<String, Object> failure(String provider, String error, long elapsedMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", false);
        out.put("provider", provider);
        out.put("response", "");
        out.put("error", error);
        out.put("latencyMs", elapsedMs);
        out.put("pipelineStep", provider + " 실패");
        return out;
    }
}
