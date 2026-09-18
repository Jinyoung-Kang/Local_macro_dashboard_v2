"""
tests/test_fx_history.py
환율 비교 차트가 쓸 저장본(fx_history)의 규칙.

고정하는 규칙
  1. 차트 티커는 매크로 카드의 fx 카테고리와 **같은 것**을 쓴다.
     (다르면 카드의 최근값과 차트의 끝값이 어긋나고, 보는 사람은 둘 중
      무엇이 맞는지 알 수 없습니다.)
  2. 엔/원 100엔당 환산은 카드와 차트가 **같은 판정 함수**를 쓴다.
     (한쪽에만 있으면 같은 지표가 930원과 9.3원으로 갈라집니다.)
  3. 전부 실패하면 기존 저장본을 덮어쓰지 않는다.
"""
from __future__ import annotations

import pytest

from app import catalog, indicators, tasks
from app.services import market as market_service


def test_chart_series_use_the_same_tickers_as_the_cards():
    specs = indicators.fx_history_specs()
    card_tickers = {
        item["ticker"]
        for category in indicators.MACRO_CATEGORIES
        if category["id"] == "fx"
        for item in category["items"]
    }

    assert {spec["ticker"] for spec in specs} == card_tickers
    assert {spec["key"] for spec in specs} == {"dxy", "usdkrw", "usdjpy", "jpykrw"}
    # 축 라벨을 붙이려면 단위가 있어야 합니다.
    assert all(spec.get("unit") for spec in specs)


def test_jpykrw_scale_matches_the_card_rule():
    # Yahoo가 1엔당(9.3원)으로 주면 100을 곱해 "100엔당"으로 맞춥니다.
    assert market_service.quote_scale("jpykrw", 9.3) == 100.0
    # 이미 100엔당(930원)으로 오면 그대로 둡니다.
    assert market_service.quote_scale("jpykrw", 930.0) == 1.0
    # 다른 지표는 건드리지 않습니다.
    assert market_service.quote_scale("usdkrw", 1381.0) == 1.0


def test_fx_task_applies_scale_and_stores_series(store, monkeypatch):
    monkeypatch.setattr(
        tasks.sector_service, "collect_daily_closes",
        lambda tickers, period="5y": {
            "tickers": {
                "KRW=X": {"dates": ["2026-09-10", "2026-09-11"], "close": [1370.0, 1381.7]},
                "JPYKRW=X": {"dates": ["2026-09-10", "2026-09-11"], "close": [9.2, 9.3]},
            },
            "error": None,
        },
    )

    detail = tasks.task_fx_history()

    assert "2/4 계열" in detail
    payload = store.read_snapshot(catalog.SNAP_FX_HISTORY).payload

    # 원/달러는 그대로.
    assert payload["series"]["usdkrw"]["close"][-1] == pytest.approx(1381.7)
    assert payload["series"]["usdkrw"]["scale"] == 1.0

    # 엔/원은 100엔당으로 환산되어 저장됩니다.
    assert payload["series"]["jpykrw"]["scale"] == 100.0
    assert payload["series"]["jpykrw"]["close"][-1] == pytest.approx(930.0)

    # 수집하지 못한 계열은 0으로 채우지 않고 아예 넣지 않습니다.
    assert "dxy" not in payload["series"]


def test_fx_task_keeps_previous_snapshot_when_everything_fails(store, monkeypatch):
    store.put_snapshot(catalog.SNAP_FX_HISTORY, {"period": "5y", "series": {"usdkrw": {}}})

    monkeypatch.setattr(
        tasks.sector_service, "collect_daily_closes",
        lambda tickers, period="5y": {"tickers": {}, "error": "Yahoo 차단"},
    )

    with pytest.raises(tasks.EmptyResult) as caught:
        tasks.task_fx_history()

    assert "0/4 계열" in str(caught.value)
    assert "Yahoo 차단" in str(caught.value)
    # 기존 저장본이 그대로 남아 있어야 합니다.
    assert "usdkrw" in store.read_snapshot(catalog.SNAP_FX_HISTORY).payload["series"]
