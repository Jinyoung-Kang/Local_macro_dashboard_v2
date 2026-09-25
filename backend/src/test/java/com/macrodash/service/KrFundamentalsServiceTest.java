package com.macrodash.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KrFundamentalsServiceTest {

    @Test
    @DisplayName("코드 형식을 검증하고 개수를 제한한다 (쿼리로 임의 문자열이 흘러들지 않게)")
    void validatesCodes() {
        assertThat(KrFundamentalsService.parseCodes("005930, 000660,abc,12345,1234567,005930"))
                .containsExactly("005930", "000660");
        String many = String.join(",", java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> String.format("%06d", i)).toList());
        assertThat(KrFundamentalsService.parseCodes(many)).hasSize(KrFundamentalsService.MAX_CODES);
    }

    @Test
    @DisplayName("저장본의 계정으로 지표를 만들고, 없는 종목은 available=false")
    @SuppressWarnings("unchecked")
    void describesStoredCompanies() throws Exception {
        StoreReader store = mock(StoreReader.class);
        String json = """
                {"source":"DART","companies":{"005930":{"name":"삼성전자","bsnsYear":"2025","fsDiv":"CFS",
                 "rceptNo":"20260311000001","accounts":{
                   "부채총계":{"current":100},"자본총계":{"current":400},
                   "매출액":{"current":300,"previous":250}}}}}
                """;
        when(store.read(eq(Datasets.SNAP_DART_FUNDAMENTALS), anyLong(), anyString())).thenReturn(Optional.of(
                new Snapshot(Datasets.SNAP_DART_FUNDAMENTALS, new ObjectMapper().readTree(json), "json", "ok", null, Instant.now())));

        Map<String, Object> out = new KrFundamentalsService(store).fundamentals("005930,069500");
        List<Map<String, Object>> companies = (List<Map<String, Object>>) out.get("companies");

        assertThat(companies.get(0).get("debtRatio")).isEqualTo(25.0);
        assertThat(companies.get(0).get("fsLabel")).isEqualTo("연결");
        assertThat(companies.get(0).get("dartUrl")).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260311000001");
        assertThat(companies.get(1)).containsEntry("code", "069500").containsEntry("available", false);
    }
}
