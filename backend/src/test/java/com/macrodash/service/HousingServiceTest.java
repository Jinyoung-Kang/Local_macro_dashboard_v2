package com.macrodash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrodash.Kst;
import com.macrodash.store.Datasets;
import com.macrodash.store.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HousingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode row(LocalDate month, String lawd, String trades) throws Exception {
        return MAPPER.readTree("{\"obsDate\":\"" + month + "\",\"lawd\":\"" + lawd + "\",\"name\":\"구" + lawd
                + "\",\"trades\":" + trades + "}");
    }

    @Test
    @DisplayName("최근 두 달은 잠정, 기준월은 25개 구가 모두 모인 잠정 아닌 최근 달, 전년 동월 대비")
    @SuppressWarnings("unchecked")
    void seoulSummary() throws Exception {
        LocalDate thisMonth = Kst.today().withDayOfMonth(1);
        LocalDate reference = thisMonth.minusMonths(2);
        List<JsonNode> rows = new ArrayList<>();
        for (int gu = 0; gu < 25; gu++) {
            String lawd = String.valueOf(11000 + gu);
            rows.add(row(reference, lawd, "[[110000, 84.0]]"));
            rows.add(row(reference.minusYears(1), lawd, "[[100000, 84.0]]"));
        }
        rows.add(row(thisMonth, "11000", "[[120000, 84.0]]"));  // 이번 달: 1개 구만, 잠정

        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readObservations(eq(Datasets.OBS_MOLIT_APT), isNull(), any(LocalDate.class), isNull()))
                .thenReturn(rows);

        Map<String, Object> out = new HousingService(repository).seoul();

        assertThat(out.get("referenceMonth")).isEqualTo(reference.toString().substring(0, 7));
        List<Map<String, Object>> months = (List<Map<String, Object>>) out.get("months");
        Map<String, Object> last = months.get(months.size() - 1);
        assertThat(last.get("provisional")).isEqualTo(true);
        assertThat(last.get("coverage")).isEqualTo(1);

        List<Map<String, Object>> districts = (List<Map<String, Object>>) out.get("districts");
        assertThat(districts).hasSize(25);
        assertThat((Double) districts.get(0).get("priceYoyPct")).isCloseTo(10.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("저장본이 없으면 available=false")
    void empty() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readObservations(any(), any(), any(), any())).thenReturn(List.of());
        assertThat(new HousingService(repository).seoul().get("available")).isEqualTo(false);
    }
}
