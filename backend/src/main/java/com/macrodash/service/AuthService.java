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
import java.time.Instant;
import java.util.Date;

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

    public AuthService(AppProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256은 최소 32바이트를 요구합니다. 짧은 값이 들어오면 기동을 막는
            // 대신 경고하고 패딩합니다(로컬 실행 편의). 운영에서는 반드시 설정하세요.
            log.warn("dashboard.jwt-secret이 32바이트 미만입니다. 운영 환경에서는 "
                    + "충분히 긴 무작위 값을 설정하세요.");
            byte[] padded = new byte[32];
            System.arraycopy(secret, 0, padded, 0, secret.length);
            for (int i = secret.length; i < 32; i++) {
                padded[i] = (byte) ('x' + i);
            }
            secret = padded;
        }
        this.key = Keys.hmacShaKeyFor(secret);
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
