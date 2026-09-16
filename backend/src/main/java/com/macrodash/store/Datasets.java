package com.macrodash.store;

/**
 * 데이터셋 이름과 신선도 기준의 단일 출처 (Java 쪽).
 *
 * <p><b>중요</b> — 이 파일은 수집기의 {@code collector/app/catalog.py}와 <b>같은 문자열</b>을
 * 정의해야 합니다. 한쪽이 오타를 내면 "수집은 되는데 화면에는 안 보이는" 버그가
 * 생기고, 그 증상만으로는 원인을 찾기 어렵습니다. 두 파일이 어긋나지 않는지는
 * {@code DatasetsParityTest}가 실제 catalog.py를 읽어 대조합니다.
 */
public final class Datasets {

    private Datasets() {
    }

    // ---------------------------------------------------------------- 스냅샷
    public static final String SNAP_MACRO_COLLECTED = "macro.collected";
    public static final String SNAP_SCRAPER_MARKETS = "macro.scraper_markets";
    public static final String SNAP_FED_LIQUIDITY = "liquidity.fed_net";
    public static final String SNAP_KRX_FUTURES = "krx.futures_history";
    public static final String SNAP_SECTOR_HISTORY = "sector.etf_history";
    public static final String SNAP_COT_HISTORY = "cot.multi_asset";

    /** 변동성 지수는 가장 긴 기간으로 한 번 저장하고 짧은 기간은 잘라 씁니다. */
    public static final String VOLATILITY_STORE_PERIOD = "5y";

    /** 수집기가 저장하는 최대 분기 수. q1은 q8의 앞부분입니다. */
    public static final int MAX_TRACKED_QUARTERS = 8;

    /** Daum 선물 수급 조회 기간 (수집기와 동일해야 합니다). */
    public static final int DAUM_TREND_LOOKBACK = 25;

    public static String daumFuturesTrend(int lookbackDays) {
        // .CONTRACT 접미사는 "계약수 기준"임을 명시합니다.
        // (금액(억원) 모드는 Daum이 제공하지 않아 제거됐습니다.)
        return "krx.daum_futures_trend.d" + lookbackDays + ".CONTRACT";
    }

    public static String tickerHistory(String symbol, String period) {
        String safe = symbol.replace("^", "").replace("=", "_").replace(".", "_");
        return "ticker." + safe + "." + period;
    }

    public static String cotContract(String contractCode, int limit) {
        return "cot.contract." + contractCode + ".l" + limit;
    }

    public static String sec13f(String cik, int maxQuarters) {
        return "sec.13f." + cik + ".q" + maxQuarters;
    }

    public static String fredSeries(String seriesId) {
        return "fred.series." + seriesId;
    }

    public static String radarScanner(String market, String investor,
                                      String tradeType, String intervalType) {
        return "radar.scanner." + market + "." + investor + "." + tradeType + "." + intervalType;
    }

    // ------------------------------------------------------------- 누적 이력
    public static final String TS_FRED = "fred";
    public static final String TS_KRX_FUTURES = "krx_futures";
    public static final String TS_LIQUIDITY = "fed_liquidity";
    public static final String OBS_RADAR = "radar_ranking";

    // ------------------------------------------------------------- 신선도(초)
    /** 장중 시세성 데이터 (수집 주기 5분의 3배). */
    public static final long MAX_AGE_REALTIME = 15 * 60L;
    /** 일별 확정치 (FRED/KRX 마감). */
    public static final long MAX_AGE_DAILY = 6 * 60 * 60L;
    /** 분기 공시(13F) 등 거의 변하지 않는 데이터. */
    public static final long MAX_AGE_SLOW = 24 * 60 * 60L;
}
