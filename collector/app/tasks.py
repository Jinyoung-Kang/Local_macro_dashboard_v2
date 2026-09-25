"""
app/tasks.py
수집 작업 정의 (구버전 collector.py의 11개 태스크).

[작업군과 주기 — 구버전과 동일]
  fast   (5분)  : scraper_markets · macro_collected · radar_rankings
  slow   (1시간): fred_series · fed_liquidity · krx_futures · sector_history · fx_history ·
                  volatility_history · cot_history · daum_futures_trend
  weekly (12시간): sec_13f · kr_holidays · dart_fundamentals

[규칙]
- 수집이 예외 없이 끝났지만 쓸 데이터가 없으면 EmptyResult로 **실패 집계**
  합니다. 이것을 성공으로 보고하면 "✅ 0/10 수집" 같은 모순된 로그가 남고
  운영자가 장애를 알아채지 못합니다.
- 빈 결과로 **기존 저장본을 덮어쓰지 않습니다.** 네트워크 일시 장애로 어제
  받아 둔 데이터를 날리면 안 됩니다.
- 추정치(isEstimated=true)는 **누적 테이블에 넣지 않습니다.** 한 번 섞이면
  실제 확정치와 구분할 수 없습니다.
"""
from __future__ import annotations

import logging
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable
from zoneinfo import ZoneInfo

from . import catalog, equities, http, indicators, publicapi, store
from .services import (
    cot as cot_service,
    dart as dart_service,
    fred as fred_service,
    kasi as kasi_service,
    krx as krx_service,
    liquidity as liquidity_service,
    market as market_service,
    radar as radar_service,
    scraper as scraper_service,
    sec13f as sec_service,
    sector as sector_service,
)

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

# 수급 레이더에서 미리 받아 둘 조건 조합. 전부 받으면 수집 시간이 과도하게
# 길어지므로 화면 기본값만 받습니다.
RADAR_COMBINATIONS = (
    ("KOSPI", "외국인", "순매수", "TODAY"),
    ("KOSPI", "기관", "순매수", "TODAY"),
    ("KOSPI", "외국인", "순매도", "TODAY"),
)

DAUM_TREND_LOOKBACK = 25


class EmptyResult(Exception):
    """수집은 끝났지만 쓸 수 있는 데이터가 없음 → 실패로 집계합니다."""


def _reason_suffix(reasons: list[str | None]) -> str:
    """
    실패 사유를 메시지 끝에 덧붙입니다.

    "0/12 시리즈"만 남으면 운영자가 무엇을 고쳐야 할지 알 수 없습니다.
    같은 사유가 반복되는 경우가 대부분이므로 중복을 제거하고 두 개까지만
    싣습니다(상태 화면의 detail 길이 제한 1000자를 넘기지 않기 위함).
    """
    unique: list[str] = []
    for reason in reasons:
        if reason and reason not in unique:
            unique.append(reason)
    if not unique:
        return ""
    return " (사유: " + " / ".join(unique[:2]) + ")"


@dataclass(frozen=True)
class Task:
    name: str
    speed: str            # fast | slow | weekly
    run: Callable[[], str]
    description: str


# ==============================================================================
# fast
# ==============================================================================
def task_scraper_markets() -> str:
    result = scraper_service.collect_scraped_markets()
    items = result.get("items", [])
    ok = sum(1 for item in items if item.get("status") == "ok")

    if not ok:
        raise EmptyResult(f"0/{len(items)} 소스 — 기존 저장본 유지")

    store.put_snapshot(catalog.SNAP_SCRAPER_MARKETS, result)
    return f"{ok}/{len(items)} 소스 수집"


def task_macro_collected() -> str:
    payload = market_service.collect_macro_cards(indicators.MACRO_CATEGORIES)
    payload = _apply_bond_override(payload)
    payload = _inject_scraped_indices(payload)

    items = [
        item
        for category in payload["categories"]
        for item in category["items"]
    ]
    usable = sum(1 for item in items if item.get("status") in ("ok", "single"))

    if not usable:
        raise EmptyResult(f"0/{len(items)} 지표 — 기존 저장본 유지")

    payload["updatedAt"] = market_service.now_kst_text()
    store.put_snapshot(catalog.SNAP_MACRO_COLLECTED, payload)
    return f"{usable}/{len(items)} 지표 수집"


def task_radar_rankings() -> str:
    today = datetime.now(KST).date()
    ok = 0

    for market, investor, trade_type, interval in RADAR_COMBINATIONS:
        result = radar_service.collect_radar_ranking(
            today, market, investor, trade_type, 30, interval
        )
        rows = result.get("rows") or []
        if not rows:
            logger.info(
                "수급 조합 빈 결과(저장본 유지): %s/%s/%s/%s",
                market, investor, trade_type, interval,
            )
            continue

        name = catalog.snap_radar_scanner(market, investor, trade_type, interval)
        store.put_snapshot(name, result)

        # 누적은 외부에서 새로 받은 값만 합니다. 이력에서 꺼내 온 값을 다시
        # 이력에 쓰면 같은 데이터가 다른 날짜로 복제됩니다.
        if not result.get("isHistorical"):
            radar_service.accumulate_history(
                rows, market, investor, trade_type, interval
            )
        ok += 1

    if not ok:
        raise EmptyResult(f"0/{len(RADAR_COMBINATIONS)} 조합 — 기존 저장본 유지")
    return f"{ok}/{len(RADAR_COMBINATIONS)} 조합 수집"


# ==============================================================================
# slow
# ==============================================================================
def task_fred_series() -> str:
    series_ids = indicators.FRED_ALL_SERIES
    ok = 0
    accumulated = 0
    reasons: list[str] = []

    for series_id in series_ids:
        points, reason = fred_service.collect_series_with_reason(
            series_id, period_years=10
        )
        if not points:
            logger.info("FRED 빈 결과(저장본 유지): %s (%s)", series_id, reason)
            if reason:
                reasons.append(reason)
            continue

        store.put_snapshot(
            catalog.snap_fred_series(series_id),
            {"seriesId": series_id, "points": points},
        )
        accumulated += store.put_timeseries(
            catalog.TS_FRED,
            series_id,
            [(p["date"], p["value"]) for p in points],
        )
        ok += 1

    if not ok:
        raise EmptyResult(
            f"0/{len(series_ids)} 시리즈 — 기존 저장본 유지{_reason_suffix(reasons)}"
        )
    return f"{ok}/{len(series_ids)} 시리즈, 누적 {accumulated}행"


def task_fed_liquidity() -> str:
    payload = liquidity_service.collect_fed_liquidity(10)
    rows = payload.get("rows") or []
    if not rows:
        raise EmptyResult(
            "순유동성 빈 결과 — 기존 저장본 유지"
            + _reason_suffix([payload.get("error")] if payload.get("error") else [])
        )

    store.put_snapshot(
        catalog.SNAP_FED_LIQUIDITY,
        payload,
        status="estimated" if payload.get("isEstimated") else "ok",
    )

    if payload.get("isEstimated"):
        return f"{len(rows)}행 (⚠️ 추정치 — 누적 제외)"

    accumulated = 0
    for column in ("walcl", "wtregen", "rrpM", "netLiquidityM"):
        accumulated += store.put_timeseries(
            catalog.TS_LIQUIDITY,
            column,
            [(row["date"], row[column]) for row in rows],
        )
    return f"{len(rows)}행, 누적 {accumulated}행"


def task_krx_futures() -> str:
    payload = krx_service.collect_futures_history(40)
    rows = payload.get("rows") or []
    if not rows:
        raise EmptyResult(
            "KRX 선물 빈 결과 — 기존 저장본 유지"
            + _reason_suffix([payload.get("error")] if payload.get("error") else [])
        )

    store.put_snapshot(
        catalog.SNAP_KRX_FUTURES,
        payload,
        status="estimated" if payload.get("isEstimated") else "ok",
    )

    if payload.get("isEstimated"):
        # ⚠️ 추정치는 누적하지 않습니다. 실제 확정치와 섞이면 구분 불가능합니다.
        return f"{len(rows)}행 (⚠️ 추정치 — 누적 제외)"

    accumulated = 0
    for column in ("futuresClose", "volume", "openInterest"):
        accumulated += store.put_timeseries(
            catalog.TS_KRX_FUTURES,
            column,
            [(row["date"], row.get(column)) for row in rows],
        )
    return f"{len(rows)}행, 누적 {accumulated}행"


def task_sector_history() -> str:
    tickers = indicators.all_rotation_tickers()
    payload = sector_service.collect_etf_history(tickers, period="2y")
    collected = payload.get("tickers") or {}

    if not collected:
        raise EmptyResult(
            f"0/{len(tickers)} 티커 — 기존 저장본 유지"
            + _reason_suffix([payload.get("error")] if payload.get("error") else [])
        )

    store.put_snapshot(catalog.SNAP_SECTOR_HISTORY, payload)
    return f"{len(collected)}/{len(tickers)} 티커"


def task_fx_history() -> str:
    """
    💱 환율·달러인덱스 일별 종가 (여러 계열 겹쳐 보기용).

    티커는 매크로 카드의 fx 카테고리에서 그대로 가져옵니다. 차트만 다른
    티커를 쓰면 카드의 최근값과 차트의 끝값이 어긋나고, 보는 사람은 둘 중
    무엇이 맞는지 알 수 없습니다.

    엔/원은 Yahoo가 '1엔당 원'을 줄 때가 있어 카드와 같은 배율 판정을
    거칩니다(market_service.quote_scale). 이 판정이 한쪽에만 있으면 같은
    지표가 카드에서는 930원, 차트에서는 9.3원으로 그려집니다.
    """
    specs = indicators.fx_history_specs()
    if not specs:
        raise EmptyResult("환율 계열 정의가 비어 있습니다 (indicators.MACRO_CATEGORIES 확인)")

    tickers = tuple(spec["ticker"] for spec in specs)
    raw = sector_service.collect_daily_closes(tickers, period=indicators.FX_HISTORY_PERIOD)
    frames = raw.get("tickers") or {}

    series: dict[str, dict] = {}
    for spec in specs:
        frame = frames.get(spec["ticker"])
        closes = (frame or {}).get("close") or []
        dates = (frame or {}).get("dates") or []
        if not closes or not dates:
            continue

        scale = market_service.quote_scale(spec["key"], closes[-1])
        series[spec["key"]] = {
            "key": spec["key"],
            # 화면 버튼 순서. JSONB는 키 순서를 보존하지 않아, 순서를 값으로
            # 적어 두지 않으면 목록이 가나다순으로 뒤집힙니다.
            "order": len(series),
            "name": spec["name"],
            "ticker": spec["ticker"],
            "unit": spec.get("unit"),
            # 배율을 함께 저장합니다. 나중에 값만 보고는 100엔당인지 1엔당인지
            # 되짚을 수 없습니다.
            "scale": scale,
            "dates": dates,
            "close": [value * scale for value in closes],
        }

    if not series:
        raise EmptyResult(
            f"0/{len(specs)} 계열 — 기존 저장본 유지"
            + _reason_suffix([raw.get("error")] if raw.get("error") else [])
        )

    store.put_snapshot(
        catalog.SNAP_FX_HISTORY,
        {"period": indicators.FX_HISTORY_PERIOD, "series": series},
    )
    return f"{len(series)}/{len(specs)} 계열"


def task_equity_history() -> str:
    """
    📈 13F 매핑 종목 + 벤치마크의 일별 종가.

    구루 포트폴리오 위험 분석(베타·추적오차·VaR)과 종목 스코어카드가 씁니다.

    **매핑표를 가격과 같은 저장본에 함께 싣습니다.** 백엔드가 매핑표를 따로
    들고 있으면 한쪽에만 종목을 추가하는 순간 조용히 어긋납니다. 단일 출처로
    둬야 "왜 이 종목만 분석에서 빠지지?"를 한 곳에서 확인할 수 있습니다.
    """
    tickers = equities.all_tickers()
    raw = sector_service.collect_daily_closes(
        tickers, period=equities.PRICE_HISTORY_PERIOD
    )
    frames = raw.get("tickers") or {}

    if not frames:
        raise EmptyResult(
            f"0/{len(tickers)} 티커 — 기존 저장본 유지"
            + _reason_suffix([raw.get("error")] if raw.get("error") else [])
        )

    store.put_snapshot(catalog.SNAP_EQUITY_HISTORY, {
        "period": equities.PRICE_HISTORY_PERIOD,
        "tickers": frames,
        # 이름 → {ticker, sector}. 백엔드가 13F 종목명을 여기에 대조합니다.
        "nameMap": equities.mapping_payload(),
        "benchmarks": equities.BENCHMARKS,
    })
    return f"{len(frames)}/{len(tickers)} 티커"


def task_volatility_history() -> str:
    """
    ^VIX / ^MOVE 시계열.

    화면이 여러 기간으로 요청하므로 가장 긴 기간(5y)으로 한 번만 저장하고,
    짧은 기간은 백엔드가 잘라 씁니다.

    ⚠️ ^MOVE는 Yahoo가 제공하지 않아 ^TNX 변동성에서 역산한 추정치입니다.
    isProxy 플래그가 저장되며 화면이 경고를 띄웁니다.
    """
    period = catalog.VOLATILITY_STORE_PERIOD
    ok = 0
    reasons: list[str] = []

    for symbol in ("^VIX", "^MOVE"):
        payload = market_service.collect_ticker(symbol, period)
        if not payload.get("points"):
            logger.info(
                "변동성 빈 결과(저장본 유지): %s (%s)", symbol, payload.get("error")
            )
            if payload.get("error"):
                reasons.append(f"{symbol} {payload['error']}")
            continue
        store.put_snapshot(
            catalog.snap_ticker_history(symbol, period),
            payload,
            status="estimated" if payload.get("isProxy") else "ok",
        )
        ok += 1

    if not ok:
        raise EmptyResult("0/2 지수 — 기존 저장본 유지" + _reason_suffix(reasons))
    return f"{ok}/2 지수 ({period})"


def task_cot_history() -> str:
    result = cot_service.collect_multi_asset(
        indicators.COT_ASSETS, indicators.COT_WEEKS
    )
    assets = result.get("assets") or {}
    usable = {name: entry for name, entry in assets.items() if entry.get("rows")}

    if not usable:
        raise EmptyResult(f"0/{len(assets)} 자산 — 기존 저장본 유지")

    store.put_snapshot(catalog.SNAP_COT_HISTORY, result)

    # 화면은 자산별로도 조회하므로 계약 코드별 스냅샷을 함께 적재합니다.
    per_contract = 0
    for name, entry in usable.items():
        store.put_snapshot(
            catalog.snap_cot_contract(entry["code"], indicators.COT_WEEKS),
            {"code": entry["code"], "asset": name, "rows": entry["rows"]},
        )
        per_contract += 1

    return f"{len(usable)}/{len(assets)} 자산 (계약별 {per_contract}건)"


def task_daum_futures_trend() -> str:
    payload = krx_service.collect_daum_futures_trend(DAUM_TREND_LOOKBACK)
    if not payload.get("rows"):
        raise EmptyResult("빈 결과 — 기존 저장본 유지")

    store.put_snapshot(
        catalog.snap_daum_futures_trend(DAUM_TREND_LOOKBACK), payload
    )
    return f"{len(payload['rows'])}개 주체 (기준일 {payload.get('dataDate')})"


# ==============================================================================
# weekly
# ==============================================================================
def task_sec_13f() -> str:
    """
    SEC 13F — 전체 수집에서 가장 오래 걸리는 작업.

    [최적화 2가지 — 구버전과 동일]
    1) q1은 q8의 부분집합입니다(공시를 최신순으로 훑어 앞에서 자릅니다).
       q8만 수집하고 q1은 잘라서 저장합니다 (수집 24건 → 12건).
    2) SEC 한도는 토큰 버킷이 전역으로 지키므로 기관을 병렬 처리해도
       합계 한도를 넘지 않습니다.
    """
    # 설정이 없으면 기관 12곳에 같은 실패를 12번 만들지 않고 여기서 한 번 말합니다.
    try:
        http.sec_user_agent()
    except http.SecUserAgentInvalid as exc:
        raise EmptyResult(f"0/{len(indicators.INSTITUTIONS)} 기관 — {exc}") from exc

    quarters = catalog.MAX_TRACKED_QUARTERS
    targets = [(inst["name"], inst["cik"]) for inst in indicators.INSTITUTIONS]

    ok = 0
    saved = 0
    errors: list[str] = []

    def collect(item):
        name, cik = item
        return name, cik, sec_service.collect_13f(cik, quarters)

    with ThreadPoolExecutor(max_workers=4) as pool:
        for name, cik, payload in pool.map(collect, targets):
            if not payload.get("quarters"):
                logger.info("13F 빈 결과(저장본 유지): %s (%s)", name, payload.get("error"))
                errors.append(f"{name}: {payload.get('error') or '빈 결과'}")
                continue

            store.put_snapshot(catalog.snap_sec_13f(cik, quarters), payload)
            saved += 1

            # q1은 q8의 첫 분기와 같은 데이터입니다. 재수집하지 않습니다.
            store.put_snapshot(
                catalog.snap_sec_13f(cik, 1),
                {**payload, "quarters": payload["quarters"][:1]},
            )
            saved += 1
            ok += 1

    if not ok:
        raise EmptyResult(
            f"0/{len(targets)} 기관 — 기존 저장본 유지"
            + (f" ({errors[0]})" if errors else "")
        )

    detail = f"{ok}/{len(targets)} 기관, 스냅샷 {saved}건 (q{quarters} 수집 → q1 유도)"
    if errors:
        detail += f", 실패 {len(errors)}곳"
    return detail


# ==============================================================================
# 매크로 카드 보정
# ==============================================================================
def task_kr_holidays() -> str:
    """
    📅 한국 공휴일 (천문연 특일정보) — 올해·내년.

    저장 규칙
      - 연도별로 합칩니다. 이번에 받지 못한 해는 이전 저장본을 그대로 둡니다.
      - **0건 응답이 기존 목록을 지우지 않게** 합니다. 아직 발표되지 않은 해는
        정상적으로 0건이 오지만, 이미 받아 둔 해가 0건으로 바뀌는 것은 데이터가
        아니라 부재(서비스 만료·일시 오류)일 가능성이 큽니다.
      - 지난해까지만 남깁니다. 시계는 올해·내년만 봅니다.
    """
    now = datetime.now(KST)
    years = (now.year, now.year + 1)

    previous = store.read_snapshot(catalog.SNAP_KR_HOLIDAYS)
    stored: dict = dict(((previous.payload or {}).get("years") or {}) if previous else {})

    fetched, kept, reasons = [], [], []
    for year in years:
        try:
            holidays = kasi_service.fetch_year(year)
        except publicapi.MissingKey:
            raise EmptyResult("DATA_GO_KR_SERVICE_KEY 미설정 — .env에 공공데이터포털 인증키를 넣으세요") from None
        except publicapi.PublicApiError as exc:
            reasons.append(f"{year}: {exc}")
            continue

        old = (stored.get(str(year)) or {}).get("holidays") or []
        if not holidays and old:
            kept.append(year)
            reasons.append(f"{year}: 0건 응답 — 기존 {len(old)}건 유지")
            continue
        stored[str(year)] = {
            "holidays": holidays,
            # 0건은 "휴일이 없다"가 아니라 "아직 발표 전"입니다. 화면이 구분할 수 있게 적습니다.
            "announced": bool(holidays),
            "fetchedAt": now.isoformat(),
        }
        fetched.append(year)

    if not fetched:
        raise EmptyResult("0/2 연도 — 기존 저장본 유지" + _reason_suffix(reasons))

    stored = {y: v for y, v in stored.items() if y.isdigit() and int(y) >= now.year - 1}
    store.put_snapshot(catalog.SNAP_KR_HOLIDAYS, {
        "source": "한국천문연구원 특일정보 (getRestDeInfo, isHoliday=Y)",
        "years": stored,
    })
    counts = ", ".join(f"{y} {len(stored[str(y)]['holidays'])}건" for y in fetched)
    return counts + (f" (유지: {', '.join(map(str, kept))})" if kept else "")


# DART 재무를 받을 종목 수 상한과 대상 기간. 호출 수 = 상한 / BATCH (+ 이전 연도 재시도).
DART_UNIVERSE_LIMIT = 150
DART_UNIVERSE_DAYS = 30
DART_CALL_BUDGET = 40
_CORP_CODES_MAX_AGE = timedelta(days=7)


def _dart_universe() -> list[str]:
    """최근 30일 수급 레이더에 등장한 종목 + 지금 화면에 걸린 종목."""
    since = (datetime.now(KST).date() - timedelta(days=DART_UNIVERSE_DAYS)).isoformat()
    codes = store.recent_observation_codes(catalog.OBS_RADAR, since, DART_UNIVERSE_LIMIT)
    for market, investor, trade_type, interval in RADAR_COMBINATIONS:
        snap = store.read_snapshot(catalog.snap_radar_scanner(market, investor, trade_type, interval))
        for row in ((snap.payload or {}).get("rows") or []) if snap else []:
            code = str(row.get("code") or "")
            if code and code not in codes:
                codes.append(code)
    return codes[:DART_UNIVERSE_LIMIT]


def _dart_corp_codes() -> dict[str, dict]:
    """고유번호 표. 일주일 안에 받은 것이 있으면 다시 받지 않습니다(수십 MB 파일)."""
    snap = store.read_snapshot(catalog.SNAP_DART_CORP_CODES)
    if snap and snap.payload and snap.collected_at and \
            datetime.now(timezone.utc) - snap.collected_at < _CORP_CODES_MAX_AGE:
        return snap.payload.get("codes") or {}
    codes = dart_service.fetch_corp_codes()
    store.put_snapshot(catalog.SNAP_DART_CORP_CODES, {"codes": codes, "count": len(codes)})
    return codes


def _latest_annual_year(today) -> int:
    """
    사업보고서가 나와 있을 가장 최근 사업연도.

    12월 결산 법인의 사업보고서 제출 기한은 다음 해 3월 말입니다. 4월 전에는
    작년 보고서가 없는 회사가 많으므로 재작년을 봅니다.
    """
    return today.year - 1 if today.month >= 4 else today.year - 2


def task_dart_fundamentals() -> str:
    """
    📑 DART 사업보고서 주요계정 — 수급 레이더에 오른 국내 종목.

    규칙
      - 최신 사업연도로 받고, 보고서가 없는 회사만 한 해 전으로 다시 받습니다.
      - 호출 예산(DART_CALL_BUDGET) 안에서만 진행합니다. 남은 종목은 다음 주기.
      - 이번에 받지 못한 종목은 이전 저장본을 유지합니다(부분 실패로 지우지 않음).
    """
    universe = _dart_universe()
    if not universe:
        raise EmptyResult("대상 종목이 없습니다 — 수급 레이더(radar_rankings)가 먼저 쌓여야 합니다")

    try:
        corp_map = _dart_corp_codes()
    except publicapi.MissingKey:
        raise EmptyResult("DART_API_KEY 미설정 — .env에 Open DART 인증키를 넣으세요") from None
    except publicapi.PublicApiError as exc:
        raise EmptyResult(f"고유번호 표를 받지 못했습니다 — {exc}") from None

    targets = {code: corp_map[code] for code in universe if code in corp_map}
    unmapped = [code for code in universe if code not in corp_map]  # ETF·ETN 등은 DART 대상이 아님

    budget = publicapi.CallBudget(DART_CALL_BUDGET)
    year = _latest_annual_year(datetime.now(KST).date())
    fetched: dict[str, dict] = {}
    reasons: list[str] = []

    for attempt_year in (year, year - 1):
        pending = [code for code in targets if code not in fetched]
        for start in range(0, len(pending), dart_service.BATCH):
            if not budget.take():
                reasons.append("호출 예산 소진 — 나머지는 다음 주기")
                break
            chunk = pending[start:start + dart_service.BATCH]
            try:
                rows = dart_service.fetch_accounts([targets[c]["corpCode"] for c in chunk], attempt_year)
            except publicapi.PublicApiError as exc:
                reasons.append(str(exc))
                continue
            for code, company in dart_service.group_by_company(rows).items():
                if code in targets and code not in fetched:
                    company["name"] = targets[code]["name"]
                    company["corpCode"] = targets[code]["corpCode"]
                    fetched[code] = company

    if not fetched:
        raise EmptyResult(f"0/{len(targets)} 종목 — 기존 저장본 유지" + _reason_suffix(reasons))

    previous = store.read_snapshot(catalog.SNAP_DART_FUNDAMENTALS)
    companies = dict(((previous.payload or {}).get("companies") or {}) if previous else {})
    companies.update(fetched)
    store.put_snapshot(catalog.SNAP_DART_FUNDAMENTALS, {
        "source": "금융감독원 Open DART 다중회사 주요계정 (사업보고서)",
        "companies": companies,
        "universe": len(universe),
        "unmapped": unmapped[:50],
    })
    return (f"{len(fetched)}/{len(targets)} 종목 ({year}년 기준, 호출 {budget.used}회)"
            + (f" · DART 대상 아님 {len(unmapped)}" if unmapped else "") + _reason_suffix(reasons))


def _apply_bond_override(payload: dict) -> dict:
    """
    미국채 카드를 TradingView Scanner의 실제 수익률로 보정합니다.

    [왜 필요한가] config의 2년물 티커는 ZT=F(2년 국채 **선물 가격**, ~100pt)라
    "수익률(%)" 라벨과 단위가 맞지 않습니다. 화면만 따로 보정하면 AI 텍스트와
    스프레드 계산은 보정되지 않은 값을 쓰게 되므로, **수집 시점에 한 번**
    보정해 이후 모든 소비자가 같은 값을 보게 합니다.

    전일 종가를 Scanner가 못 주면 FRED 공식 일별 확정치(DGS2/10/30)로
    보완하고, 출처가 다르다는 사실을 prevSource에 남깁니다. 어느 쪽도 없으면
    0.00%로 위장하지 않고 N/A로 둡니다.
    """
    snapshot = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    scraped: dict[str, dict] = {}

    # 같은 수집 라운드에서 방금 저장된 값이 있으면 재사용합니다
    # (같은 실행 안에서 외부 스크래핑이 두 번 일어나는 낭비를 막습니다).
    if snapshot and snapshot.is_fresh(300) and snapshot.payload:
        scraped = {
            item["key"]: item
            for item in (snapshot.payload.get("items") or [])
            if isinstance(item, dict)
        }
    else:
        try:
            scraped = {
                item["key"]: item
                for item in scraper_service.collect_scraped_markets()["items"]
            }
        except Exception as exc:  # noqa: BLE001
            logger.warning("국채 보정용 스크래핑 실패: %s", exc)
            return payload

    now_text = market_service.now_kst_text()

    for category in payload["categories"]:
        for item in category["items"]:
            key = item.get("key")
            if key not in indicators.BOND_SCANNER_KEYS:
                continue

            source = scraped.get(key)
            if not source or source.get("status") != "ok":
                continue

            price = source.get("price")
            if price is None:
                continue

            price = float(price)
            item.update({
                "price": price,
                "priceStr": f"{price:,.3f}",
                "status": "ok",
                "source": source.get("provider", "TradingView Scanner"),
                # Scanner 응답에는 체결 시각이 없으므로 "수집 시각"임을 밝힙니다.
                "lastTs": f"{now_text} (TradingView 수집 시각)",
            })

            previous = source.get("previousClose")
            prev_source = "TradingView"

            if previous is None or float(previous) == 0:
                fred_prev = _bond_previous_from_fred(key)
                if fred_prev is not None:
                    previous = fred_prev
                    prev_source = "FRED 공식 확정치"

            if previous and float(previous) != 0:
                previous = float(previous)
                delta = price - previous
                pct = delta / previous * 100.0
                item.update({
                    "delta": delta,
                    "pct": pct,
                    "prevStr": f"{previous:,.3f}",
                    "prevValue": previous,
                    "deltaStr": f"{delta:+,.3f} ({pct:+.2f}%)",
                    "prevSource": prev_source,
                })
            else:
                item.update({
                    "delta": None, "pct": None,
                    "prevStr": "N/A", "prevValue": None, "deltaStr": "N/A",
                })

            # 보정된 값을 스프레드 계산용 rates에도 그대로 반영합니다.
            # 위 루프가 이미 BOND_SCANNER_KEYS만 통과시키므로, 여기서 키를
            # 다시 추리면 목록이 두 곳으로 갈라져 한쪽만 30년물을 빠뜨리게
            # 됩니다(그러면 30Y−2Y 스크래핑 패널이 "수집 실패"로 뜹니다).
            payload.setdefault("rates", {})[key] = {
                "current": item["price"],
                "previous": item.get("prevValue"),
            }

    return payload


def _bond_previous_from_fred(key: str) -> float | None:
    """
    FRED 일별 확정치에서 직전 영업일 수익률을 읽습니다.

    FRED는 하루 지연 발표이므로 시리즈의 마지막 값이 곧 직전 거래일
    확정치입니다. 수집기가 이미 이 시리즈를 적재해 두므로 추가 네트워크
    비용도 없습니다.
    """
    series_id = indicators.BOND_FRED_FALLBACK.get(key)
    if not series_id:
        return None

    snapshot = store.read_snapshot(catalog.snap_fred_series(series_id))
    if snapshot and snapshot.payload:
        points = snapshot.payload.get("points") or []
        if points:
            value = points[-1].get("value")
            return float(value) if value and value > 0 else None

    points = fred_service.collect_series(series_id, period_years=1)
    value = fred_service.latest_value(points)
    return value if value and value > 0 else None


def _inject_scraped_indices(payload: dict) -> dict:
    """
    아시아 지수 카테고리에 스크래핑 기반 참고 시세를 덧붙입니다.

    구버전은 코스피200 야간선물·닛케이225 선물·항셍 선물을 별도 스크래퍼로
    주입했습니다. 그 스크래퍼들은 TradingView HTML 정규식에 의존해 조용히
    깨지는 경로였으므로, 같은 값을 JSON으로 주는 Symbol Scanner 결과
    (services/scraper.py)로 대체합니다. 카드에는 출처와 추정 여부를 남깁니다.
    """
    snapshot = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    if not snapshot or not snapshot.payload:
        return payload

    scraped = {
        item["key"]: item
        for item in (snapshot.payload.get("items") or [])
        if isinstance(item, dict)
    }

    target = next(
        (c for c in payload["categories"] if c["id"] == indicators.SCRAPED_INJECT_CATEGORY),
        None,
    )
    if target is None:
        return payload

    existing = {item.get("key") for item in target["items"]}

    for key, label in (
        ("kospi200_night", "코스피200 야간선물 (CME 연계)"),
        ("nikkei", "닛케이225 선물"),
        ("hang_seng", "항셍 선물"),
    ):
        source = scraped.get(key)
        card_key = f"{key}_scraped"
        if not source or card_key in existing:
            continue

        if source.get("status") != "ok" or source.get("price") is None:
            target["items"].append({
                "key": card_key, "name": label, "status": "fail",
                "source": source.get("provider"),
            })
            continue

        price = float(source["price"])
        previous = source.get("previousClose")
        card = {
            "key": card_key,
            "name": label,
            "note": source.get("note") or "참고 시세",
            "status": "ok",
            "price": price,
            "priceStr": f"{price:,.2f}",
            "source": source.get("provider"),
            "isReference": True,
            # 추정치를 확정치처럼 보여 주면 교차 검증이 무의미해집니다.
            "isEstimated": bool(source.get("isEstimated")),
        }
        if previous:
            previous = float(previous)
            delta = price - previous
            pct = delta / previous * 100.0 if previous else 0.0
            card.update({
                "delta": delta, "pct": pct,
                "deltaStr": f"{delta:+,.2f} ({pct:+.2f}%)",
                "prevStr": f"{previous:,.2f}", "prevValue": previous,
            })
        else:
            card.update({
                "delta": None, "pct": None,
                "deltaStr": "N/A", "prevStr": "N/A", "prevValue": None,
            })
        target["items"].append(card)

    return payload


# ==============================================================================
# 태스크 목록 및 실행
# ==============================================================================
ALL_TASKS: tuple[Task, ...] = (
    Task("scraper_markets", "fast", task_scraper_markets, "TradingView/Yahoo 참고 시세"),
    Task("macro_collected", "fast", task_macro_collected, "매크로 카드 전 지표"),
    Task("radar_rankings", "fast", task_radar_rankings, "국내 수급 랭킹 (이력 누적)"),
    Task("fred_series", "slow", task_fred_series, "FRED 금리/신용 시계열 (이력 누적)"),
    Task("fed_liquidity", "slow", task_fed_liquidity, "연준 순유동성 (이력 누적)"),
    Task("krx_futures", "slow", task_krx_futures, "KRX 선물/미결제약정 (이력 누적)"),
    Task("sector_history", "slow", task_sector_history, "섹터·자산군 ETF 종가"),
    Task("fx_history", "slow", task_fx_history, "환율·달러인덱스 일별 종가"),
    Task("equity_history", "slow", task_equity_history, "13F 매핑 종목 일별 종가"),
    Task("volatility_history", "slow", task_volatility_history, "VIX·MOVE 변동성 시계열"),
    Task("cot_history", "slow", task_cot_history, "CFTC COT (주 1회 발표)"),
    Task("daum_futures_trend", "slow", task_daum_futures_trend, "Daum 선물 투자주체별 수급"),
    Task("sec_13f", "weekly", task_sec_13f, "SEC 13F 기관 포트폴리오 (분기 공시)"),
    Task("kr_holidays", "weekly", task_kr_holidays, "한국 공휴일 (천문연 특일정보)"),
    Task("dart_fundamentals", "weekly", task_dart_fundamentals, "국내 종목 재무 (DART 사업보고서)"),
)

TASKS_BY_NAME = {task.name: task for task in ALL_TASKS}


# ==============================================================================
# 같은 태스크의 동시 실행 합치기 (coalescing)
# ==============================================================================
# 화면 한 번 여는 것만으로 같은 태스크가 여러 번 돕니다. 백엔드는 스냅샷을
# 읽다가 오래됐으면 수집을 요청하는데, 한 화면이 여러 스냅샷을 병렬로 읽으면
# 그 요청이 각각 나갑니다. 실제 로그에서 확인된 모습입니다.
#
#   05:02:02 수집 시작 (cot_history): 1개 작업   ← 같은 초에 4번
#   05:02:02 수집 시작 (cot_history): 1개 작업
#   05:02:02 수집 시작 (cot_history): 1개 작업
#   05:02:02 수집 시작 (cot_history): 1개 작업
#
#   05:02:26 ✅ fred_series 80.03s   ← 스케줄러(slow)
#   05:03:07 ✅ fred_series 36.30s   ← 요청 1
#   05:03:08 ✅ fred_series 50.43s   ← 요청 2  (셋이 동시에 FRED를 두드림)
#
# 외부 API 호출이 그대로 몇 배가 되고(FRED·SEC는 호출 한도가 있습니다),
# 같은 행을 동시에 쓰게 됩니다.
#
# 뒤늦게 온 호출을 **거절하지 않고 기다리게** 합니다. 호출자가 원하는 것은
# "지금 새로 받아라"가 아니라 "신선한 값을 달라"이기 때문입니다. 이미 도는
# 수집이 끝나면 그 결과를 함께 씁니다.
_COALESCE_WAIT_SECONDS = 180.0

_inflight: dict[str, "_InFlight"] = {}
_inflight_lock = threading.Lock()


class _InFlight:
    """실행 중인 태스크 하나와 그 결과를 기다리는 자리."""

    __slots__ = ("done", "result")

    def __init__(self) -> None:
        self.done = threading.Event()
        self.result: tuple[bool, str] = (False, "결과 없음")


def run_task(task: Task, run_id: int | None = None) -> tuple[bool, str]:
    """
    태스크 1건을 실행하고 결과를 DB에 남깁니다.

    같은 태스크가 이미 돌고 있으면 새로 시작하지 않고 그 결과를 기다립니다.
    """
    with _inflight_lock:
        entry = _inflight.get(task.name)
        leader = entry is None
        if leader:
            entry = _InFlight()
            _inflight[task.name] = entry

    if not leader:
        return _wait_for_inflight(task, entry)

    try:
        result = _execute_task(task, run_id)
        entry.result = result
        return result
    except BaseException as exc:      # 기다리는 쪽을 영원히 붙잡아 두지 않습니다.
        entry.result = (False, f"{type(exc).__name__}: {exc}")
        raise
    finally:
        # 먼저 등록을 지워야 다음 호출이 새 수집을 시작할 수 있습니다.
        with _inflight_lock:
            _inflight.pop(task.name, None)
        entry.done.set()


def _wait_for_inflight(task: Task, entry: "_InFlight") -> tuple[bool, str]:
    """이미 도는 같은 태스크가 끝나기를 기다렸다가 그 결과를 씁니다."""
    logger.info("%s: 이미 실행 중입니다. 새로 시작하지 않고 결과를 기다립니다.", task.name)

    if not entry.done.wait(timeout=_COALESCE_WAIT_SECONDS):
        return False, (
            f"이미 실행 중인 수집을 {int(_COALESCE_WAIT_SECONDS)}초 기다렸지만 "
            "끝나지 않았습니다"
        )

    ok, detail = entry.result
    return ok, f"{detail} (동시에 실행 중이던 수집 결과를 함께 사용)"


def _execute_task(task: Task, run_id: int | None) -> tuple[bool, str]:
    started_wall = datetime.now(timezone.utc)
    started = time.perf_counter()

    status = "ok"
    ok = True
    detail = ""

    try:
        detail = task.run() or ""
    except EmptyResult as exc:
        status, ok, detail = "empty", False, f"수집 결과 없음: {exc}"
    except Exception as exc:  # noqa: BLE001
        status, ok = "error", False
        detail = f"{type(exc).__name__}: {exc}"
        logger.debug(traceback.format_exc())

    elapsed = time.perf_counter() - started
    icon = {"ok": "✅", "empty": "⚠️", "error": "❌"}[status]
    (logger.info if ok else logger.warning)(
        "  %s %-22s %6.2fs  %s", icon, task.name, elapsed, detail
    )

    store.record_task_run(
        run_id,
        task.name,
        speed=task.speed,
        status=status,
        started_at=started_wall,
        duration_ms=int(elapsed * 1000),
        detail=detail,
    )
    return ok, detail


def run_group(group: str | None = None, task_name: str | None = None) -> dict:
    """
    작업군 또는 단일 태스크를 1회 실행합니다.

    반환: {"group","okCount","failCount","elapsedSeconds","results":[...]}
    """
    if task_name:
        selected = [TASKS_BY_NAME[task_name]] if task_name in TASKS_BY_NAME else []
    elif group in (None, "all"):
        selected = list(ALL_TASKS)
    else:
        selected = [task for task in ALL_TASKS if task.speed == group]

    label = task_name or group or "all"
    if not selected:
        logger.warning("실행할 작업이 없습니다 (%s)", label)
        return {"group": label, "okCount": 0, "failCount": 0, "results": []}

    logger.info("수집 시작 (%s): %d개 작업", label, len(selected))

    run_id = None
    try:
        run_id = store.start_run(group_name=label)
    except Exception as exc:  # noqa: BLE001
        logger.warning("실행 로그 기록 실패(수집은 계속합니다): %s", exc)

    started = time.perf_counter()
    results = []
    ok_count = 0
    failures: list[str] = []

    for task in selected:
        success, detail = run_task(task, run_id)
        results.append({
            "task": task.name,
            "speed": task.speed,
            "ok": success,
            "detail": detail,
        })
        if success:
            ok_count += 1
        else:
            failures.append(f"{task.name}: {detail}")

        # 태스크마다 heartbeat를 남겨, 죽은 수집기를 "진행 중"으로 오인하지
        # 않게 합니다 (13F는 한 태스크가 10분 넘게 걸립니다).
        if run_id is not None:
            store.heartbeat_run(run_id)

    elapsed = time.perf_counter() - started

    if run_id is not None:
        try:
            store.finish_run(
                run_id,
                status="ok" if not failures else ("partial" if ok_count else "fail"),
                ok_count=ok_count,
                fail_count=len(failures),
                detail="; ".join(failures) or None,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("실행 로그 마감 실패: %s", exc)

    logger.info(
        "수집 완료 (%s): 성공 %d · 실패 %d · %.1fs",
        label, ok_count, len(failures), elapsed,
    )

    return {
        "group": label,
        "runId": run_id,
        "okCount": ok_count,
        "failCount": len(failures),
        "elapsedSeconds": round(elapsed, 2),
        "results": results,
    }
