package com.macrodash.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    @DisplayName("쿼리 파라미터·Bearer·서비스 토큰 헤더를 가리고, 진단에 필요한 값은 남긴다")
    void redacts() {
        String text = "GET /fred/series?series_id=WALCL&api_key=deadbeef1234 "
                + "https://apis.data.go.kr/x?serviceKey=Abc%2B12&basDt=20260923 "
                + "crtfc_key=0123456789abcdef Authorization: Bearer eyJhbGciOiJIUzI1NiJ9 X-Service-Token: s3cr3t";
        String out = SecretRedactor.redact(text);

        assertThat(out).doesNotContain("deadbeef1234", "Abc%2B12", "0123456789abcdef", "eyJhbGciOiJIUzI1NiJ9", "s3cr3t");
        assertThat(out).contains("series_id=WALCL", "basDt=20260923", "api_key=***");
    }

    @Test
    @DisplayName("비밀이 아닌 파라미터(sort_key 등)는 건드리지 않는다")
    void leavesOrdinaryText() {
        String text = "sort_key=date&monkey=1 수집 결과 없음";
        assertThat(SecretRedactor.redact(text)).isEqualTo(text);
        assertThat(SecretRedactor.redact(null)).isNull();
    }
}
