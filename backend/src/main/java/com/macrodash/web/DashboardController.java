package com.macrodash.web;

import com.macrodash.service.AnalyticsService;
import com.macrodash.service.CotService;
import com.macrodash.service.DataStatusService;
import com.macrodash.service.KrxService;
import com.macrodash.service.LiquidityService;
import com.macrodash.service.MacroService;
import com.macrodash.service.RadarService;
import com.macrodash.service.Sec13FService;
import com.macrodash.service.SectorService;
import com.macrodash.service.SnapshotTextService;
import com.macrodash.service.VerificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 화면용 REST API — 12개 메뉴에 대응합니다.
 *
 * <pre>
 *  📊 거시경제 매크로 지표      GET /api/macro/*
 *  🏢 연준 순유동성 트래커      GET /api/liquidity
 *  🔄 섹터 & 자산군 로테이션    GET /api/sector/*
 *  📑 기관 13F 포트폴리오       GET /api/sec13f/*
 *  🎯 기관 13F Money 교집합     GET /api/sec13f/consensus
 *  🏛️ 글로벌 투기세력 (COT)     GET /api/cot/*
 *  🇰🇷 국내 파생 & 투기세력      GET /api/krx/*
 *  📡 외국인/기관 수급 레이더    GET /api/radar/*
 *  🗄️ 데이터 저장소 상태         GET /api/status, /api/verification
 *  📋 전체 원본 데이터            GET /api/snapshot/text
 *  🔗 지표 상관관계               GET /api/analytics/correlation
 *  🧭 시장 국면                   GET /api/analytics/regime
 *  🤖 AI 리포트 · 연결 테스트    → AiController
 *  🔌 토스증권 API 테스트        → AiController(진단 묶음)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final MacroService macro;
    private final LiquidityService liquidity;
    private final SectorService sector;
    private final Sec13FService sec13f;
    private final CotService cot;
    private final KrxService krx;
    private final RadarService radar;
    private final DataStatusService status;
    private final VerificationService verification;
    private final SnapshotTextService snapshotText;
    private final AnalyticsService analytics;

    public DashboardController(MacroService macro, LiquidityService liquidity,
                               SectorService sector, Sec13FService sec13f, CotService cot,
                               KrxService krx, RadarService radar, DataStatusService status,
                               VerificationService verification,
                               SnapshotTextService snapshotText,
                               AnalyticsService analytics) {
        this.macro = macro;
        this.liquidity = liquidity;
        this.sector = sector;
        this.sec13f = sec13f;
        this.cot = cot;
        this.krx = krx;
        this.radar = radar;
        this.status = status;
        this.verification = verification;
        this.snapshotText = snapshotText;
        this.analytics = analytics;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    // ------------------------------------------------------- 📊 매크로
    /**
     * @param live 매크로 화면의 자동 갱신이 켜져 있으면 true.
     *             저장본을 다시 받을 기준이 15분 → 60초로 내려갑니다.
     *             (더 짧게 두지 않는 이유는 Datasets.MAX_AGE_LIVE 주석에)
     */
    @GetMapping("/macro/overview")
    public Map<String, Object> macroOverview(
            @RequestParam(name = "live", defaultValue = "false") boolean live) {
        return macro.overview(live);
    }

    @GetMapping("/macro/risk")
    public Map<String, Object> macroRisk() {
        return macro.riskIndicators();
    }

    @GetMapping("/macro/advanced")
    public Map<String, Object> macroAdvanced() {
        return macro.advancedIndicators();
    }

    @GetMapping("/macro/scraped")
    public Map<String, Object> macroScraped() {
        return macro.scrapedMarkets();
    }

    @GetMapping("/macro/spread")
    public Map<String, Object> macroSpread(
            @RequestParam(defaultValue = "DGS10") String longId,
            @RequestParam(defaultValue = "DGS2") String shortId) {
        return macro.officialSpread(longId, shortId);
    }

    @GetMapping("/macro/fred/{seriesId}")
    public Map<String, Object> fredSeries(@PathVariable String seriesId,
                                          @RequestParam(required = false) Integer years) {
        return macro.fredSeries(seriesId, years);
    }

    @GetMapping("/macro/ticker")
    public Map<String, Object> ticker(@RequestParam String symbol,
                                      @RequestParam(defaultValue = "1y") String period) {
        return macro.tickerSeries(symbol, period);
    }

    // ------------------------------------------------------ 🏢 순유동성
    @GetMapping("/liquidity")
    public Map<String, Object> liquidity(@RequestParam(required = false) Integer years) {
        return liquidity.netLiquidity(years);
    }

    // ------------------------------------------------------ 🔄 로테이션
    @GetMapping("/sector/rotation")
    public Map<String, Object> rotation(@RequestParam(defaultValue = "1M") String period) {
        return sector.rotation(period);
    }

    @GetMapping("/sector/momentum")
    public Map<String, Object> momentum() {
        return sector.momentum();
    }

    // --------------------------------------------------------- 📑 13F
    @GetMapping("/sec13f/institutions")
    public Map<String, Object> institutions() {
        return sec13f.institutionList();
    }

    @GetMapping("/sec13f/portfolio")
    public Map<String, Object> portfolio(@RequestParam String cik,
                                         @RequestParam(defaultValue = "8") int quarters,
                                         @RequestParam(defaultValue = "30") int topN) {
        return sec13f.portfolio(cik, quarters, topN);
    }

    @GetMapping("/sec13f/consensus")
    public Map<String, Object> consensus(
            @RequestParam(required = false) String ciks,
            @RequestParam(required = false) String reportDate,
            @RequestParam(defaultValue = "2") int minHolders,
            @RequestParam(defaultValue = "30") int topN) {

        List<String> selected = (ciks == null || ciks.isBlank())
                ? Sec13FService.INSTITUTIONS.stream().map(entry -> entry.get("cik")).toList()
                : Arrays.stream(ciks.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();

        return sec13f.consensus(selected, reportDate, minHolders, topN);
    }

    /** 🆕 이번 분기에 여러 기관이 함께 새로 담은 종목. */
    @GetMapping("/sec13f/new-buys")
    public Map<String, Object> newBuys(
            @RequestParam(required = false) String ciks,
            @RequestParam(required = false) String reportDate,
            @RequestParam(defaultValue = "3") int minHolders) {

        List<String> selected = (ciks == null || ciks.isBlank())
                ? Sec13FService.INSTITUTIONS.stream().map(entry -> entry.get("cik")).toList()
                : Arrays.stream(ciks.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();

        return sec13f.newBuys(selected, reportDate, minHolders);
    }

    // --------------------------------------------------------- 🏛️ COT
    @GetMapping("/cot/assets")
    public Map<String, Object> cotAssets() {
        return cot.assetList();
    }

    @GetMapping("/cot/overview")
    public Map<String, Object> cotOverview() {
        return cot.overview();
    }

    @GetMapping("/cot/asset")
    public Map<String, Object> cotAsset(@RequestParam String name) {
        return cot.asset(name);
    }

    /** 극단 포지션 이후 4·13주 수익률 분포 (가격은 ETF 대용). */
    @GetMapping("/cot/extremes")
    public Map<String, Object> cotExtremes(
            @RequestParam String name,
            @RequestParam(defaultValue = "95") double percentile,
            @RequestParam(defaultValue = "52") int lookbackWeeks) {
        return cot.extremes(name, percentile, lookbackWeeks);
    }

    // --------------------------------------------------------- 🇰🇷 KRX
    @GetMapping("/krx/futures")
    public Map<String, Object> krxFutures(@RequestParam(defaultValue = "40") Integer days) {
        return krx.futures(days);
    }

    @GetMapping("/krx/investor-trend")
    public Map<String, Object> krxInvestorTrend() {
        return krx.investorTrend();
    }

    @GetMapping("/krx/intraday")
    public Map<String, Object> krxIntraday(@RequestParam(defaultValue = "30") int minutes) {
        return krx.intradayAcceleration(minutes);
    }

    @GetMapping("/krx/oi-trend")
    public Map<String, Object> krxOpenInterestTrend() {
        return krx.openInterestTrend();
    }

    // -------------------------------------------------------- 📡 레이더
    @GetMapping("/radar/options")
    public Map<String, Object> radarOptions() {
        return Map.of(
                "markets", RadarService.MARKETS,
                "investors", RadarService.INVESTORS,
                // 화면이 "지원 안 함"을 표시할 수 있도록 함께 내려보냅니다.
                // 목록만 주면 고를 수 있는 것과 없는 것이 똑같아 보입니다.
                "supportedInvestors", RadarService.SUPPORTED_INVESTORS,
                "unsupportedInvestorNote", RadarService.UNSUPPORTED_INVESTOR_NOTE,
                "tradeTypes", RadarService.TRADE_TYPES,
                "intervals", RadarService.INTERVALS);
    }

    @GetMapping("/radar/ranking")
    public Map<String, Object> radarRanking(
            @RequestParam(defaultValue = "KOSPI") String market,
            @RequestParam(defaultValue = "외국인") String investor,
            @RequestParam(defaultValue = "순매수") String tradeType,
            @RequestParam(defaultValue = "30") int topN,
            @RequestParam(defaultValue = "TODAY") String intervalType,
            @RequestParam(required = false) String targetDate) {
        return radar.ranking(market, investor, tradeType, topN, intervalType, targetDate);
    }

    @GetMapping("/radar/history")
    public Map<String, Object> radarHistory(
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String investor,
            @RequestParam(required = false) String tradeType,
            @RequestParam(required = false) String obsDate,
            @RequestParam(required = false) String startDate) {
        return radar.history(market, investor, tradeType, obsDate, startDate);
    }

    @GetMapping("/radar/diagnostics")
    public Map<String, Object> radarDiagnostics() {
        return radar.diagnostics();
    }

    // ------------------------------------------------- 🗄️ 저장소 상태
    @GetMapping("/status")
    public Map<String, Object> status() {
        return status.status();
    }

    @GetMapping("/status/tasks")
    public Map<String, Object> tasks() {
        return status.tasks();
    }

    @GetMapping("/status/history")
    public Map<String, Object> taskHistory(@RequestParam(required = false) String task,
                                           @RequestParam(defaultValue = "40") int limit) {
        return status.taskHistory(task, limit);
    }

    @PostMapping("/status/refresh")
    public Map<String, Object> refresh(@RequestParam(defaultValue = "true") boolean runFast) {
        return status.refresh(runFast);
    }

    @PostMapping("/status/run/{taskName}")
    public Map<String, Object> runTask(@PathVariable String taskName) {
        return status.runTask(taskName);
    }

    @PostMapping("/verification")
    public Map<String, Object> verification() {
        return verification.run();
    }

    // ------------------------------------------- 🔗 상관관계 · 🧭 국면
    /** 상관 분석에 쓸 수 있는 계열 목록. */
    @GetMapping("/analytics/series")
    public Map<String, Object> analyticsSeries() {
        return Map.of("series", analytics.catalog());
    }

    /**
     * 두 계열의 상관관계.
     *
     * @param mode change(기본, 변화끼리) 또는 level(수준끼리 — 허위 상관 주의)
     */
    @GetMapping("/analytics/correlation")
    public Map<String, Object> correlation(
            @RequestParam String x,
            @RequestParam String y,
            @RequestParam(defaultValue = "60") int window,
            @RequestParam(defaultValue = "3") int years,
            @RequestParam(defaultValue = "change") String mode) {
        return analytics.correlation(x, y, window, years, mode);
    }

    /** 성장·신용 축 × 유동성 축으로 판정한 시장 국면. */
    @GetMapping("/analytics/regime")
    public Map<String, Object> regime(@RequestParam(defaultValue = "5") int years) {
        return analytics.regime(years);
    }

    // -------------------------------------------- 📋 전체 원본 데이터
    /**
     * 수집한 전체 대시보드 원본 텍스트 (AI 분석 없음 · 화면 표시/복사용).
     *
     * <p>AI 메뉴의 {@code /api/ai/snapshot-text}와 같은 텍스트지만 경로를 나눠
     * 둡니다. 원본 데이터를 보는 일은 AI 키가 없어도 되는 기능인데, AI 경로
     * 아래에 두면 "AI 기능"으로 읽히기 때문입니다.
     */
    @GetMapping("/snapshot/text")
    public Map<String, Object> snapshotText() {
        return snapshotText.payload();
    }
}
