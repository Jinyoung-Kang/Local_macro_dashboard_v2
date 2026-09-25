package com.macrodash.web;

import com.macrodash.service.CalendarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public PublicDataController(CalendarService calendar) {
        this.calendar = calendar;
    }

    /** 📅 한국 공휴일 (천문연 특일정보) — 시계의 KRX 휴장 판정용. */
    @GetMapping("/calendar/kr-holidays")
    public Map<String, Object> krHolidays() {
        return calendar.krHolidays();
    }
}
