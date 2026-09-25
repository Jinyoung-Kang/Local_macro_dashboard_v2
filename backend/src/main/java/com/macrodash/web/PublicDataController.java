package com.macrodash.web;

import com.macrodash.service.CalendarService;
import com.macrodash.service.KrFundamentalsService;
import com.macrodash.service.KrMarketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 국내 공공 API(공공데이터포털·Open DART)로 모은 데이터.
 *
 * <p>DashboardController와 나눈 이유 — 그쪽은 이미 서비스 14개를 주입받습니다.
 * 출처와 키가 같은 것끼리 묶어 두면, 키가 없을 때 어떤 화면이 비는지도
 * 이 파일 하나로 보입니다.
 */
@RestController
@RequestMapping("/api")
public class PublicDataController {

    private final CalendarService calendar;
    private final KrFundamentalsService fundamentals;
    private final KrMarketService market;

    public PublicDataController(CalendarService calendar, KrFundamentalsService fundamentals,
                                KrMarketService market) {
        this.calendar = calendar;
        this.fundamentals = fundamentals;
        this.market = market;
    }

    /** 📅 한국 공휴일 (천문연 특일정보) — 시계의 KRX 휴장 판정용. */
    @GetMapping("/calendar/kr-holidays")
    public Map<String, Object> krHolidays() {
        return calendar.krHolidays();
    }

    /**
     * 📑 국내 종목 재무 안정성·성장성 (DART 사업보고서).
     *
     * @param codes 쉼표로 구분한 6자리 종목코드 (최대 60개, 형식이 틀린 코드는 무시)
     */
    @GetMapping("/kr/fundamentals")
    public Map<String, Object> krFundamentals(@RequestParam(defaultValue = "") String codes) {
        return fundamentals.fundamentals(codes);
    }

    /**
     * 🏛️ 시장별 시가총액·거래대금 합계 (금융위 공식 시세).
     *
     * @param days 조회 기간(일, 20~400)
     */
    @GetMapping("/kr/market-totals")
    public Map<String, Object> krMarketTotals(@RequestParam(defaultValue = "180") int days) {
        return market.totals(days);
    }
}
