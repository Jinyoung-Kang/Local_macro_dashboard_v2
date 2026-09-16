"""
tests/test_market_cards.py
매크로 카드 규칙 회귀 테스트.

구버전에서 실제로 화면을 잘못 그리게 만들었던 상황들을 고정합니다.
외부 네트워크를 쓰지 않습니다(수집 함수를 대체합니다).
"""
from __future__ import annotations

import pytest

from app.services import market


def _points(*closes, base_date="2026-09-11T09:00:00+09:00"):
    from datetime import datetime, timedelta

    start = datetime.fromisoformat(base_date)
    return [
        {
            "date": (start + timedelta(minutes=index)).isoformat(),
            "open": close, "high": close, "low": close,
            "close": close, "volume": 0,
        }
        for index, close in enumerate(closes)
    ]


def _payload(points, *, intraday=True):
    return {
        "symbol": "TEST", "period": "5d", "isIntraday": intraday,
        "isProxy": False, "isSynthetic": False, "sourceLabel": None,
        "points": points,
    }


SPEC = {"key": "dxy", "name": "달러 인덱스 (DXY)", "ticker": "DX-Y.NYB", "note": "실시간"}


def test_normal_card_has_delta_and_previous():
    card = market._build_card(SPEC, _payload(_points(100.0, 101.0)))

    assert card["status"] == "ok"
    assert card["price"] == pytest.approx(101.0)
    assert card["delta"] == pytest.approx(1.0)
    assert card["pct"] == pytest.approx(1.0)
    assert card["prevStr"] == "100.00"


def test_failed_collection_is_reported_as_fail():
    card = market._build_card(SPEC, _payload([]))
    assert card["status"] == "fail"
    assert "price" not in card


def test_stalled_minute_bars_fall_back_to_daily_close(monkeypatch):
    """
    주말·비유동 시간대에는 분봉 피드가 마지막 봉을 그대로 반복합니다.
    그때 "변화 없음(0.00%)"으로 위장하면 거짓이고, 전일 종가까지 N/A로
    사라지는 것도 잘못입니다. 일봉에서 직전 거래일 종가를 찾아야 합니다.
    """
    monkeypatch.setattr(
        market, "previous_close_from_daily", lambda symbol, current=None: 9.20
    )

    spec = {"key": "jpykrw", "name": "엔/원 100엔당", "ticker": "JPYKRW=X", "note": "실시간"}
    card = market._build_card(spec, _payload(_points(9.50, 9.50)))

    assert card["status"] == "ok"
    assert card["prevSource"] == "일봉 직전 거래일 종가"
    # 배율(×100)은 현재가와 전일값에 동일하게 적용돼야 합니다.
    assert card["price"] == pytest.approx(950.0)
    assert card["prevValue"] == pytest.approx(920.0)
    assert card["delta"] == pytest.approx(30.0)


def test_no_previous_close_is_na_not_zero(monkeypatch):
    """전일값을 못 구하면 0.00%로 위장하지 않고 N/A로 둡니다."""
    monkeypatch.setattr(
        market, "previous_close_from_daily", lambda symbol, current=None: None
    )

    card = market._build_card(SPEC, _payload(_points(100.0, 100.0)))

    assert card["status"] == "single"
    assert card["delta"] is None
    assert card["pct"] is None
    assert card["deltaStr"] == "N/A"
    assert card["prevStr"] == "N/A"


def test_jpy_scale_decided_by_raw_price(monkeypatch):
    """
    배율은 **원본 현재가**로 한 번만 판정해야 합니다. 스케일 적용 후의 값으로
    판정하면 전일값만 100배 틀어집니다.
    """
    monkeypatch.setattr(
        market, "previous_close_from_daily", lambda symbol, current=None: None
    )
    spec = {"key": "jpykrw", "name": "엔/원 100엔당", "ticker": "JPYKRW=X", "note": "실시간"}
    card = market._build_card(spec, _payload(_points(9.10, 9.30)))

    assert card["price"] == pytest.approx(930.0)
    assert card["prevValue"] == pytest.approx(910.0)


def test_daily_bar_timestamp_is_labelled_not_faked():
    """
    일봉 폴백 데이터의 시:분:초는 신뢰할 수 없습니다. 거짓 체결 시각 대신
    거래일임을 명시해야 합니다.
    """
    card = market._build_card(SPEC, _payload(_points(100.0, 101.0), intraday=False))
    assert card["lastTs"].endswith("일봉 기준")


def test_intraday_timestamp_is_kst():
    card = market._build_card(SPEC, _payload(_points(100.0, 101.0)))
    assert card["lastTs"].endswith("KST")


def test_slice_period_keeps_proxy_flags():
    payload = {
        "symbol": "^MOVE", "period": "5y", "isIntraday": False,
        "isProxy": True, "isSynthetic": False,
        "sourceLabel": "^TNX 변동성 기반 추정치 (실제 ICE BofA MOVE 아님)",
        "points": _points(*range(100, 200), base_date="2020-01-01T00:00:00+00:00"),
    }
    sliced = market.slice_period(payload, "1mo")

    # 잘라 내도 "이 값은 실제 지표가 아니다"라는 표시가 사라지면 안 됩니다.
    assert sliced["isProxy"] is True
    assert "실제 ICE BofA MOVE 아님" in sliced["sourceLabel"]
    assert len(sliced["points"]) <= len(payload["points"])


def test_move_proxy_returns_empty_when_tnx_unavailable(monkeypatch):
    """
    ^TNX까지 실패하면 합성 사인파를 만들지 않고 빈 결과를 돌려줍니다.
    (구버전은 정보가 전혀 없는 시계열을 그려 줬습니다.)
    """
    monkeypatch.setattr(
        market, "_download",
        lambda symbol, period: (None, False, "yfinance가 빈 응답을 받았습니다"),
    )

    payload = market.collect_ticker("^MOVE", "3mo")
    assert payload["points"] == []
    assert payload["isProxy"] is True
    # 왜 비었는지가 남아야 합니다. 없으면 화면에 "0건"만 뜹니다.
    assert payload.get("error")
