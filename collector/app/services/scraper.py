"""
app/services/scraper.py
비공식 참고 시세 수집 (TradingView Scanner + Yahoo chart JSON).

[구버전과 달라진 점 — 의도한 개선]
구버전은 TradingView **HTML 본문을 정규식으로** 긁는 경로를 여러 개 갖고
있었습니다(_parse_tradingview, _parse_tradingview_hsi,
_extract_tradingview_current_price …). HTML 구조가 바뀌면 예외 없이 조용히
틀린 숫자를 주기 시작하는 방식이라, 이 프로젝트가 교차 검증 계층을 따로
만들어야 했던 원인이기도 합니다.

같은 값을 JSON으로 주는 공개 엔드포인트가 이미 있고 구버전도 일부는 그것을
썼습니다(bonds scanner, Symbol Scanner, Yahoo chart). 이 버전은 전부 JSON
경로만 씁니다. 수집되는 항목과 의미는 동일하고, 실패는 조용히 틀린 값이
아니라 status="fail"로 드러납니다.

⚠️ 출처의 성격은 그대로입니다. TradingView/Yahoo는 **비공식 참고 시세**이며,
화면은 반드시 그렇게 표시해야 합니다.
"""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from zoneinfo import ZoneInfo

import requests

from ..http import get_session

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

TRADINGVIEW_BONDS_SCANNER_URL = "https://scanner.tradingview.com/bonds/scan"
TRADINGVIEW_SYMBOL_SCANNER_URL = "https://scanner.tradingview.com/symbol"
YAHOO_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"

TRADINGVIEW_US_TREASURY_SYMBOLS = {
    "TVC:US02Y": "us02y",
    "TVC:US10Y": "us10y",
    "TVC:US30Y": "us30y",
}

TREASURY_KEYS = ("us02y", "us10y", "us30y")

# kind: tradingview_symbol(=Symbol Scanner JSON) | yahoo_chart(=chart JSON)
SCRAPER_MARKETS = [
    {"key": "us02y", "name": "미국채 2년물", "kind": "tradingview_symbol",
     "symbol": "TVC:US02Y", "provider": "TradingView Scanner", "unit": "%",
     "url": "https://www.tradingview.com/symbols/TVC-US02Y/"},
    {"key": "us10y", "name": "미국채 10년물", "kind": "tradingview_symbol",
     "symbol": "TVC:US10Y", "provider": "TradingView Scanner", "unit": "%",
     "url": "https://www.tradingview.com/symbols/TVC-US10Y/"},
    {"key": "us30y", "name": "미국채 30년물", "kind": "tradingview_symbol",
     "symbol": "TVC:US30Y", "provider": "TradingView Scanner", "unit": "%",
     "url": "https://www.tradingview.com/symbols/TVC-US30Y/"},
    {"key": "wti", "name": "WTI 원유", "kind": "yahoo_chart",
     "symbol": "CL=F", "provider": "Yahoo Finance", "unit": "USD/bbl",
     "url": "https://finance.yahoo.com/quote/CL=F/"},
    {"key": "brent", "name": "브렌트유", "kind": "yahoo_chart",
     "symbol": "BZ=F", "provider": "Yahoo Finance", "unit": "USD/bbl",
     "url": "https://finance.yahoo.com/quote/BZ=F/"},
    {"key": "gold_spot", "name": "금 현물", "kind": "tradingview_symbol",
     "symbol": "OANDA:XAUUSD", "provider": "TradingView Scanner", "unit": "USD/oz",
     "url": "https://www.tradingview.com/symbols/XAUUSD/"},
    {"key": "kospi", "name": "코스피", "kind": "tradingview_symbol",
     "symbol": "KRX:KOSPI", "provider": "TradingView Scanner", "unit": "pt",
     "url": "https://www.tradingview.com/symbols/KRX-KOSPI/"},
    {"key": "nikkei", "name": "닛케이225", "kind": "tradingview_symbol",
     "symbol": "TVC:NI225", "provider": "TradingView Scanner", "unit": "pt",
     "url": "https://www.tradingview.com/symbols/TVC-NI225/"},
    {"key": "shanghai", "name": "상해종합", "kind": "yahoo_chart",
     "symbol": "000001.SS", "provider": "Yahoo Finance", "unit": "pt",
     "url": "https://finance.yahoo.com/quote/000001.SS/"},
    {"key": "hang_seng", "name": "항셍", "kind": "tradingview_symbol",
     "symbol": "TVC:HSI", "provider": "TradingView Scanner", "unit": "pt",
     "url": "https://www.tradingview.com/symbols/TVC-HSI/"},
]


def collect_scraped_markets() -> dict:
    """
    참고 시세 전체를 병렬 수집합니다.

    반환: {"updatedAt": "...KST", "items": [ ... ]}
    item.status: ok | fail
    """
    results: list[dict] = []

    with ThreadPoolExecutor(max_workers=len(SCRAPER_MARKETS) + 1) as pool:
        bonds_future = pool.submit(fetch_treasury_yields)
        futures = {
            pool.submit(_collect_one, config): config
            for config in SCRAPER_MARKETS
        }
        for future, config in futures.items():
            try:
                results.append(future.result())
            except Exception as exc:  # noqa: BLE001
                results.append(_fail(config, str(exc)))

        try:
            bonds = bonds_future.result()
        except Exception as exc:  # noqa: BLE001
            logger.warning("TradingView bonds scanner 조회 실패: %s", exc)
            bonds = {}

    # bonds scanner는 "현재 수익률"만 주고 전일 종가가 없습니다. 이미 Symbol
    # Scanner가 전일 종가까지 채웠다면 그대로 두고, 현재가가 비어 있을 때만
    # 보강합니다.
    by_key = {item["key"]: item for item in results}
    for key, reading in bonds.items():
        item = by_key.get(key)
        if item is None or item.get("price") is not None:
            continue
        item.update({
            "status": "ok",
            "price": reading["price"],
            "provider": reading["provider"],
            "error": None,
        })

    order = {config["key"]: index for index, config in enumerate(SCRAPER_MARKETS)}
    results.sort(key=lambda item: order.get(item["key"], 999))

    return {
        "updatedAt": datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST"),
        "items": results,
    }


def _collect_one(config: dict) -> dict:
    try:
        if config["kind"] == "tradingview_symbol":
            price, previous, change, change_pct = fetch_symbol_snapshot(config["symbol"])
        else:
            price, previous = fetch_yahoo_chart(config["symbol"])
            change = (price - previous) if (price is not None and previous) else None
            change_pct = (
                (change / previous * 100.0) if (change is not None and previous) else None
            )
    except requests.RequestException as exc:
        return _fail(config, f"통신 실패: {exc}")
    except ValueError as exc:
        return _fail(config, f"응답 해석 실패: {exc}")

    if price is None:
        return _fail(config, "현재가를 얻지 못했습니다")

    return {
        "key": config["key"],
        "name": config["name"],
        "url": config["url"],
        "provider": config["provider"],
        "unit": config["unit"],
        "status": "ok",
        "price": price,
        "previousClose": previous,
        "change": change,
        "changePct": change_pct,
        "error": None,
    }


def _fail(config: dict, error: str) -> dict:
    return {
        "key": config["key"],
        "name": config["name"],
        "url": config["url"],
        "provider": config["provider"],
        "unit": config["unit"],
        "status": "fail",
        "price": None,
        "previousClose": None,
        "change": None,
        "changePct": None,
        "error": error,
    }


# ==============================================================================
# 개별 출처
# ==============================================================================
def fetch_symbol_snapshot(
    symbol: str,
) -> tuple[float | None, float | None, float | None, float | None]:
    """
    TradingView Symbol Scanner에서 현재가·등락을 JSON으로 읽습니다.

    반환: (현재가, 전일 종가, 변화량, 변화율%)
    전일 종가는 change_abs(절대 변화)에서 역산합니다. change_abs가 없으면
    change(%)로 역산하고, 둘 다 없으면 None으로 둡니다 — 0으로 메우지 않습니다.
    """
    params = {
        "symbol": symbol,
        "fields": "close,change,change_abs",
        "no_404": "true",
        "label-product": "symbols-performance",
    }
    response = get_session().get(
        TRADINGVIEW_SYMBOL_SCANNER_URL, params=params, timeout=10
    )
    response.raise_for_status()
    payload = response.json()

    price = _to_float(payload.get("close"))
    change_pct = _to_float(payload.get("change"))
    change = _to_float(payload.get("change_abs"))

    if price is None:
        logger.warning("Symbol Scanner 현재가 파싱 실패: symbol=%s", symbol)
        return None, None, None, None

    previous = None
    if change is not None:
        previous = price - change
    elif change_pct is not None and change_pct != -100:
        previous = price / (1 + change_pct / 100.0)
        change = price - previous

    return price, previous, change, change_pct


def fetch_yahoo_chart(symbol: str) -> tuple[float | None, float | None]:
    """Yahoo chart JSON에서 최근 종가와 직전 거래일 종가를 읽습니다."""
    response = get_session().get(
        YAHOO_CHART_URL.format(symbol=symbol),
        params={"range": "10d", "interval": "1d", "includePrePost": "false"},
        timeout=10,
    )
    response.raise_for_status()

    results = response.json().get("chart", {}).get("result") or []
    if not results:
        return None, None

    quotes = results[0].get("indicators", {}).get("quote") or []
    if not quotes:
        return None, None

    closes = [float(c) for c in (quotes[0].get("close") or []) if c is not None]
    if not closes:
        return None, None

    return closes[-1], (closes[-2] if len(closes) >= 2 else None)


def fetch_treasury_yields() -> dict:
    """
    TradingView 공개 bonds scanner에서 미국채 수익률을 한 번에 읽습니다.

    응답 예: {"s": "TVC:US02Y", "d": [1000, 1, 20280831, "P2Y", 4.375, ...]}
    d[4]가 최신 수익률(%)입니다. 이 엔드포인트는 보조 출처이며 비어 있는
    경우도 흔하므로(2026-09 기준 d=[] 응답 확인) 실패해도 조용히 넘어갑니다.
    """
    try:
        response = get_session().get(
            TRADINGVIEW_BONDS_SCANNER_URL,
            params={"label-product": "bonds-yield-curve"},
            timeout=10,
        )
        response.raise_for_status()
        rows = response.json().get("data") or []
    except (requests.RequestException, ValueError) as exc:
        logger.warning("TradingView bonds scanner 조회 실패: %s", exc)
        return {}

    out: dict[str, dict] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        key = TRADINGVIEW_US_TREASURY_SYMBOLS.get(str(row.get("s", "")).strip())
        if not key:
            continue
        values = row.get("d") or []
        if not isinstance(values, list) or len(values) < 5:
            continue
        current = _to_float(values[4])
        if current is None:
            continue
        out[key] = {
            "price": current,
            "provider": "TradingView Scanner",
            "symbol": row.get("s"),
        }

    missing = set(TREASURY_KEYS) - set(out)
    if missing:
        logger.info(
            "bonds scanner 미수집: %s — Symbol Scanner 값으로 대체합니다.",
            sorted(missing),
        )
    return out


def _to_float(value) -> float | None:
    if value is None:
        return None
    try:
        text = (
            str(value)
            .replace(",", "")
            .replace(" ", "")
            .replace("\xa0", "")
            .strip()
        )
        return float(text)
    except (TypeError, ValueError):
        return None
