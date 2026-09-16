"""
app/services/sector.py
섹터·자산군 ETF 종가 수집.

여러 티커를 한 요청으로 묶어 받습니다(yf.download). 티커마다 따로 부르면
20개 티커에 HTTP 왕복이 20번 생기고 Yahoo 레이트리밋에도 그만큼 노출됩니다.
배치가 실패하면 빠진 티커만 개별 재시도합니다.

수익률 매트릭스·모멘텀 순위 계산은 여기서 하지 않습니다. 백엔드(Java)가
저장된 종가로 계산합니다. 수집기는 "받아서 적재", 백엔드는 "계산"으로
역할을 나눠 두면 같은 원본에서 두 결과가 갈라지는 일이 없습니다.
"""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

import pandas as pd
import yfinance as yf

logger = logging.getLogger(__name__)


def collect_etf_history(tickers: tuple[str, ...], period: str = "2y") -> dict:
    """
    반환 계약(JSON):
    {"tickers": {"XLK": {"dates": ["2026-01-02", ...], "close": [123.4, ...]}}}

    종가만 저장합니다. OHLCV 전체는 화면이 쓰지 않는데 저장량만 커집니다.
    """
    symbols = [t for t in dict.fromkeys(tickers) if t]
    if not symbols:
        return {"tickers": {}}

    frames: dict[str, pd.DataFrame] = {}

    try:
        raw = yf.download(
            tickers=" ".join(symbols),
            period=period,
            interval="1d",
            auto_adjust=True,
            actions=False,
            progress=False,
            group_by="ticker",
            threads=True,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("ETF 배치 수집 실패, 개별 수집으로 폴백합니다: %s", exc)
        raw = None

    if raw is not None and not raw.empty:
        for symbol in symbols:
            try:
                if isinstance(raw.columns, pd.MultiIndex):
                    if symbol not in raw.columns.get_level_values(0):
                        continue
                    frame = raw[symbol].dropna(how="all")
                else:
                    frame = raw.dropna(how="all")
                if not frame.empty and "Close" in frame.columns:
                    frames[symbol] = frame
            except Exception as exc:  # noqa: BLE001
                logger.warning("ETF 배치 결과 분해 실패 (%s): %s", symbol, exc)

    missing = [t for t in symbols if t not in frames]
    if missing:
        logger.info("ETF 개별 재시도: %s", missing)
        with ThreadPoolExecutor(max_workers=min(len(missing), 8)) as pool:
            futures = {
                pool.submit(_fetch_single, symbol, period): symbol
                for symbol in missing
            }
            for future in as_completed(futures):
                symbol = futures[future]
                try:
                    frame = future.result()
                except Exception as exc:  # noqa: BLE001
                    logger.warning("ETF 수집 실패 (%s): %s", symbol, exc)
                    continue
                if frame is not None and not frame.empty:
                    frames[symbol] = frame

    out: dict[str, dict] = {}
    for symbol, frame in frames.items():
        closes = frame["Close"].dropna()
        if closes.empty:
            continue
        out[symbol] = {
            "dates": [pd.Timestamp(i).strftime("%Y-%m-%d") for i in closes.index],
            "close": [float(v) for v in closes.tolist()],
        }

    return {"tickers": out}


def _fetch_single(symbol: str, period: str) -> pd.DataFrame | None:
    frame = yf.Ticker(symbol).history(period=period, auto_adjust=True)
    if frame is None or frame.empty or "Close" not in frame.columns:
        return None
    return frame
