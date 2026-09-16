"""
app/tasks.py
수집 작업 정의 (구버전 collector.py의 11개 태스크).

[작업군과 주기 — 구버전과 동일]
  fast   (5분)  : scraper_markets · macro_collected · radar_rankings
  slow   (1시간): fred_series · fed_liquidity · krx_futures · sector_history ·
                  volatility_history · cot_history · daum_futures_trend
  weekly (12시간): sec_13f

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
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable
from zoneinfo import ZoneInfo

from . import catalog, indicators, store
from .services import (
    cot as cot_service,
    fred as fred_service,
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

    for series_id in series_ids:
        points = fred_service.collect_series(series_id, period_years=10)
        if not points:
            logger.info("FRED 빈 결과(저장본 유지): %s", series_id)
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
        raise EmptyResult(f"0/{len(series_ids)} 시리즈 — 기존 저장본 유지")
    return f"{ok}/{len(series_ids)} 시리즈, 누적 {accumulated}행"


def task_fed_liquidity() -> str:
    payload = liquidity_service.collect_fed_liquidity(10)
    rows = payload.get("rows") or []
    if not rows:
        raise EmptyResult("순유동성 빈 결과 — 기존 저장본 유지")

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
        raise EmptyResult("KRX 선물 빈 결과 — 기존 저장본 유지")

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
        raise EmptyResult(f"0/{len(tickers)} 티커 — 기존 저장본 유지")

    store.put_snapshot(catalog.SNAP_SECTOR_HISTORY, payload)
    return f"{len(collected)}/{len(tickers)} 티커"


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

    for symbol in ("^VIX", "^MOVE"):
        payload = market_service.collect_ticker(symbol, period)
        if not payload.get("points"):
            logger.info("변동성 빈 결과(저장본 유지): %s", symbol)
            continue
        store.put_snapshot(
            catalog.snap_ticker_history(symbol, period),
            payload,
            status="estimated" if payload.get("isProxy") else "ok",
        )
        ok += 1

    if not ok:
        raise EmptyResult("0/2 지수 — 기존 저장본 유지")
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

            if key in ("us02y", "us10y"):
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

    for key, label in (("nikkei", "닛케이225 선물"), ("hang_seng", "항셍 선물")):
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
            "note": "참고 시세",
            "status": "ok",
            "price": price,
            "priceStr": f"{price:,.2f}",
            "source": source.get("provider"),
            "isReference": True,
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
    Task("volatility_history", "slow", task_volatility_history, "VIX·MOVE 변동성 시계열"),
    Task("cot_history", "slow", task_cot_history, "CFTC COT (주 1회 발표)"),
    Task("daum_futures_trend", "slow", task_daum_futures_trend, "Daum 선물 투자주체별 수급"),
    Task("sec_13f", "weekly", task_sec_13f, "SEC 13F 기관 포트폴리오 (분기 공시)"),
)

TASKS_BY_NAME = {task.name: task for task in ALL_TASKS}


def run_task(task: Task, run_id: int | None = None) -> tuple[bool, str]:
    """태스크 1건을 실행하고 결과를 DB에 남깁니다."""
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
