package com.macrodash.web;

import com.macrodash.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 로그인 · 세션 확인 · 로그아웃. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                     HttpServletResponse response) {
        if (!authService.passwordMatches(request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "비밀번호가 올바르지 않습니다."));
        }

        Cookie cookie = new Cookie(AuthService.COOKIE_NAME, authService.issueToken());
        cookie.setHttpOnly(true);       // 스크립트가 읽을 수 없습니다 (XSS 방어)
        cookie.setPath("/");
        cookie.setMaxAge((int) authService.sessionSeconds());
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        String token = null;
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthService.COOKIE_NAME.equals(cookie.getName())) {
                    token = cookie.getValue();
                }
            }
        }
        return Map.of("authenticated", authService.isValid(token));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(AuthService.COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Map.of("ok", true);
    }
}
