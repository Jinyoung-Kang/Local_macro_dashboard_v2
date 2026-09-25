package com.macrodash.service;

import com.macrodash.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * 간이 인증 (비밀번호 잠금).
 *
 * <p>구버전은 Streamlit 세션 상태에 불린 하나를 두는 방식이었고, 화면이
 * 하나의 프로세스였기에 가능했습니다. 지금은 프런트·백엔드가 분리돼 있으므로
 * 서명된 토큰을 씁니다.
 *
 * <p>토큰은 <b>httpOnly 쿠키</b>로 내려갑니다. 브라우저 스크립트가 읽을 수
 * 없으므로 XSS로 토큰이 유출되지 않습니다.
 *
 * <p>비밀번호 비교는 {@link MessageDigest#isEqual}로 <b>길이에 관계없이 일정
 * 시간</b>에 수행합니다. 문자열 {@code equals}는 앞에서부터 비교하다 다르면
 * 즉시 끝나 타이밍 차이가 생깁니다.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public static final String COOKIE_NAME = "macro_session";

    private final AppProperties properties;
    private final SecretKey key;

    /** 저장소에 공개된 기본값들. 이 값으로 서명하면 누구나 토큰을 위조할 수 있습니다. */
    static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "change-me-please-change-me-please-32b");
    static final String DEFAULT_PASSWORD = "admin1234@";

    public AuthService(AppProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(signingSecret(properties.getJwtSecret()));
        if (DEFAULT_PASSWORD.equals(properties.getPassword())) {
            log.warn("APP_PASSWORD가 기본값입니다. 같은 네트워크의 누구나 로그인할 수 있으니 .env에서 바꾸세요.");
        }
    }

    /**
     * 서명 키 바이트를 정합니다.
     *
     * <p>설정값이 공개된 기본값이거나 32바이트(HS256 최소) 미만이면 <b>기동할 때마다
     * 무작위 키</b>를 씁니다. 예전에는 짧은 값을 정해진 바이트로 채웠는데, 그러면
     * 키가 사실상 공개돼 누구나 세션 토큰을 만들 수 있었습니다.
     *
     * <p>주의사항 — 무작위 키를 쓰면 재시작할 때 기존 세션이 모두 풀립니다.
     * 계속 로그인 상태를 유지하려면 JWT_SECRET을 설정하세요(make setup이 만들어 줍니다).
     *
     * @param configured 설정된 JWT_SECRET
     * @return HMAC 키 바이트(32바이트 이상)
     */
    static byte[] signingSecret(String configured) {
        byte[] secret = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length >= 32 && !KNOWN_PLACEHOLDERS.contains(configured)) {
            return secret;
        }
        log.warn("JWT_SECRET이 비었거나 기본값·32바이트 미만입니다. 이번 실행 동안만 쓰는 무작위 "
                + "키로 서명합니다(재시작하면 다시 로그인해야 합니다).");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return random;
    }

    public boolean passwordMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                properties.getPassword().getBytes(StandardCharsets.UTF_8));
    }

    public String issueToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("dashboard-user")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getSessionMinutes() * 60)))
                .signWith(key)
                .compact();
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getExpiration() == null
                    || claims.getExpiration().toInstant().isAfter(Instant.now());
        } catch (Exception e) {
            return false;
        }
    }

    public long sessionSeconds() {
        return properties.getSessionMinutes() * 60;
    }
}
