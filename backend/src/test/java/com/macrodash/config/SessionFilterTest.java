package com.macrodash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SessionFilterTest {

    private static MockHttpServletRequest request(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    @Test
    @DisplayName("공개 경로는 정확히 일치할 때만 인증을 건너뛴다")
    void publicPathsMatchExactly() {
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/health"))).isTrue();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/auth/login"))).isTrue();

        // 예전 접두사 비교에서는 아래가 모두 공개로 판정됐습니다.
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/healthx"))).isFalse();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/health/details"))).isFalse();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/auth/login/extra"))).isFalse();
        assertThat(WebConfig.SessionFilter.isPublic(request("/api/auth/logout"))).isFalse();
    }

    @Test
    @DisplayName("API 응답에 보안 헤더가 붙는다")
    void securityHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebConfig.SessionFilter.addSecurityHeaders(response);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
}
