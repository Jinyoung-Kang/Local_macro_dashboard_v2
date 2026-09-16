package com.macrodash.config;

import com.macrodash.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * CORS와 인증 필터.
 *
 * <p>인증은 단순합니다 — 로그인·헬스체크를 제외한 모든 {@code /api/**} 요청에
 * 유효한 세션 쿠키가 있어야 합니다. 사용자 계정 체계가 없는 1인용 대시보드라
 * 구버전의 "비밀번호 한 개" 모델을 그대로 유지합니다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 쿠키를 주고받아야 하므로 allowCredentials가 필요하고,
        // 그 경우 오리진에 와일드카드를 쓸 수 없습니다.
        List<String> origins = Arrays.stream(properties.getFrontendOrigin().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();

        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public FilterRegistrationBean<SessionFilter> sessionFilter(AuthService authService) {
        FilterRegistrationBean<SessionFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SessionFilter(authService));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    /** 세션 쿠키 검사 필터. */
    public static class SessionFilter extends OncePerRequestFilter {

        private static final List<String> PUBLIC_PATHS = List.of(
                "/api/auth/login", "/api/auth/session", "/api/health");

        private final AuthService authService;

        public SessionFilter(AuthService authService) {
            this.authService = authService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();

            if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(path)) {
                chain.doFilter(request, response);
                return;
            }

            if (authService.isValid(readToken(request))) {
                chain.doFilter(request, response);
                return;
            }

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"unauthorized\",\"message\":\"로그인이 필요합니다.\"}");
        }

        private boolean isPublic(String path) {
            return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
        }

        private String readToken(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return null;
            }
            for (Cookie cookie : cookies) {
                if (AuthService.COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
            return null;
        }
    }
}
