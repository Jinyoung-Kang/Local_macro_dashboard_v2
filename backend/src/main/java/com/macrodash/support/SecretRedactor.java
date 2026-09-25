package com.macrodash.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 밖으로 내보내는 문자열에서 비밀값을 가립니다.
 *
 * <p>수집기가 이미 실패 사유에서 키를 지우지만(collector/app/publicapi.py, logredact.py),
 * 복사해서 다른 곳에 붙이는 텍스트는 한 번 새면 되돌릴 수 없습니다. 내보내기 직전에
 * 한 번 더 거릅니다(심층 방어). 규칙은 수집기의 logredact와 같은 파라미터 이름을 씁니다.
 */
public final class SecretRedactor {

    private static final String MASK = "***";

    /** 이름만 봐도 비밀인 쿼리 파라미터 (대소문자 무시). */
    private static final Pattern QUERY = Pattern.compile(
            "(?i)\\b(api_key|apikey|auth_key|authkey|token|access_token|appkey|app_key|appsecret|"
                    + "app_secret|secret|password|servicekey|crtfc_key|key|consumer_key|"
                    + "consumer_secret|accesstoken|confmkey|client_secret)=([^&\\s\"'<>()]+)");

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}");

    /** "X-Service-Token: abc" 같은 헤더 표기. */
    private static final Pattern HEADER = Pattern.compile(
            "(?i)\\b((?:x-service-token|authorization|x-api-key)\\s*[:=]\\s*)(\\S+)");

    private SecretRedactor() {
    }

    /**
     * @param text 내보낼 문자열 (null 허용)
     * @return 비밀값이 {@code ***}로 바뀐 문자열
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = QUERY.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "=" + MASK));
        out = BEARER.matcher(out).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + MASK));
        return HEADER.matcher(out).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + MASK));
    }
}
