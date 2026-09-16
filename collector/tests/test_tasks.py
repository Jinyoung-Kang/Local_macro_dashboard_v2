"""
tests/test_tasks.py
수집 태스크 규칙 회귀 테스트 (PostgreSQL 필요).

고정하는 규칙
  1. 빈 결과로 기존 저장본을 덮어쓰지 않는다 (EmptyResult → 실패 집계).
  2. 추정치는 누적 테이블에 넣지 않는다.
  3. 태스크 결과가 collector_task_runs에 남는다 (무엇이 왜 실패했는지).
"""
from __future__ import annotations

import pytest

from app import catalog, tasks


def test_empty_result_is_counted_as_failure(store, monkeypatch):
    def failing():
        raise tasks.EmptyResult("0/10 소스 — 기존 저장본 유지")

    task = tasks.Task("demo_task", "fast", failing, "테스트용")
    ok, detail = tasks.run_task(task)

    assert ok is False
    assert "수집 결과 없음" in detail

    summary = {row["task"]: row for row in store.read_task_summary()}
    assert summary["demo_task"]["status"] == "empty"


def test_exception_is_recorded_with_type(store):
    def broken():
        raise ValueError("파싱 실패")

    ok, detail = tasks.run_task(tasks.Task("broken_task", "slow", broken, ""))

    assert ok is False
    assert detail.startswith("ValueError:")
    summary = {row["task"]: row for row in store.read_task_summary()}
    assert summary["broken_task"]["status"] == "error"


def test_empty_collection_keeps_previous_snapshot(store, monkeypatch):
    """네트워크 일시 장애로 어제 받아 둔 데이터를 날리면 안 됩니다."""
    store.put_snapshot(catalog.SNAP_SCRAPER_MARKETS, {"items": [{"key": "us10y"}]})

    monkeypatch.setattr(
        tasks.scraper_service, "collect_scraped_markets",
        lambda: {"updatedAt": "now", "items": [{"key": "us10y", "status": "fail"}]},
    )

    with pytest.raises(tasks.EmptyResult):
        tasks.task_scraper_markets()

    kept = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    assert kept.payload == {"items": [{"key": "us10y"}]}


def test_estimated_krx_futures_are_not_accumulated(store, monkeypatch):
    monkeypatch.setattr(
        tasks.krx_service, "collect_futures_history",
        lambda days: {
            "isEstimated": True,
            "rows": [{
                "date": "2026-09-11", "futuresClose": 1088.3, "changePct": -2.13,
                "changePctReported": None, "volume": None, "openInterest": None,
                "oiChange": None, "theoryPrice": None, "marketBasis": None,
                "contractName": "추정", "marketPhase": "판정 불가 (등락률 미제공)",
                "cotOiIndex": None,
            }],
        },
    )

    detail = tasks.task_krx_futures()

    assert "추정치" in detail
    assert store.read_timeseries(catalog.TS_KRX_FUTURES, "futuresClose") == []
    snapshot = store.read_snapshot(catalog.SNAP_KRX_FUTURES)
    assert snapshot.status == "estimated"


def test_confirmed_krx_futures_are_accumulated(store, monkeypatch):
    monkeypatch.setattr(
        tasks.krx_service, "collect_futures_history",
        lambda days: {
            "isEstimated": False,
            "rows": [{
                "date": "2026-09-11", "futuresClose": 1088.3, "changePct": -2.13,
                "changePctReported": -2.13, "volume": 120000.0,
                "openInterest": 310000.0, "oiChange": 10000.0,
                "theoryPrice": 1085.0, "marketBasis": 3.3,
                "contractName": "코스피200 F 202609",
                "marketPhase": "신규 숏 (Short Accumulation)", "cotOiIndex": 88.0,
            }],
        },
    )

    tasks.task_krx_futures()

    accumulated = store.read_timeseries(catalog.TS_KRX_FUTURES, "futuresClose")
    assert accumulated == [{"date": "2026-09-11", "value": 1088.3}]
    assert store.read_snapshot(catalog.SNAP_KRX_FUTURES).status == "ok"


def test_fred_task_writes_snapshot_and_history(store, monkeypatch):
    monkeypatch.setattr(
        tasks.indicators, "FRED_ALL_SERIES", ("T10Y3M",), raising=False
    )
    monkeypatch.setattr(
        tasks.fred_service, "collect_series_with_reason",
        lambda series_id, period_years=10: (
            [
                {"date": "2026-09-10", "value": 0.42},
                {"date": "2026-09-11", "value": 0.38},
            ],
            None,
        ),
    )

    detail = tasks.task_fred_series()

    assert "1/1 시리즈" in detail
    snapshot = store.read_snapshot(catalog.snap_fred_series("T10Y3M"))
    assert snapshot.payload["points"][-1]["value"] == 0.38
    assert len(store.read_timeseries(catalog.TS_FRED, "T10Y3M")) == 2


def test_fred_task_failure_detail_carries_reason(store, monkeypatch):
    """
    전부 실패했을 때 detail이 "0/1 시리즈"에서 끝나면 운영자가 무엇을
    고쳐야 할지 알 수 없습니다. 사유가 실패 메시지에 실려야 합니다.
    """
    monkeypatch.setattr(
        tasks.indicators, "FRED_ALL_SERIES", ("T10Y3M",), raising=False
    )
    monkeypatch.setattr(
        tasks.fred_service, "collect_series_with_reason",
        lambda series_id, period_years=10: (
            [], "CSV HTTP 403 — FRED_API_KEY를 설정하면 공식 API 경로로 우회됩니다"
        ),
    )

    with pytest.raises(tasks.EmptyResult) as exc:
        tasks.task_fred_series()

    assert "403" in str(exc.value)
    assert "FRED_API_KEY" in str(exc.value)


def test_run_group_summarizes_results(store, monkeypatch):
    monkeypatch.setattr(
        tasks, "ALL_TASKS",
        (
            tasks.Task("ok_task", "fast", lambda: "정상", ""),
            tasks.Task("bad_task", "fast", lambda: (_ for _ in ()).throw(RuntimeError("x")), ""),
        ),
    )

    result = tasks.run_group("fast")

    assert result["okCount"] == 1
    assert result["failCount"] == 1
    last_run = store.read_last_run()
    assert last_run["status"] == "partial"


def test_sec_13f_q1_is_derived_from_q8(store, monkeypatch):
    """q1은 q8의 앞부분입니다. 같은 데이터를 두 번 받지 않습니다."""
    calls = {"count": 0}

    def fake_collect(cik, quarters):
        calls["count"] += 1
        return {
            "cik": cik,
            "error": None,
            "quarters": [
                {"filingDate": f"2026-0{q}-15", "reportDate": f"2026-0{q}-31",
                 "totalValue": 1000.0, "holdings": []}
                for q in range(1, 4)
            ],
        }

    monkeypatch.setattr(tasks.sec_service, "collect_13f", fake_collect)
    monkeypatch.setattr(
        tasks.indicators, "INSTITUTIONS",
        [{"key": "demo", "name": "데모 기관", "cik": "0001067983", "desc": ""}],
        raising=False,
    )

    tasks.task_sec_13f()

    assert calls["count"] == 1, "기관당 1회만 수집해야 합니다"
    q8 = store.read_snapshot(catalog.snap_sec_13f("0001067983", 8))
    q1 = store.read_snapshot(catalog.snap_sec_13f("0001067983", 1))
    assert len(q8.payload["quarters"]) == 3
    assert len(q1.payload["quarters"]) == 1
    assert q1.payload["quarters"][0] == q8.payload["quarters"][0]
