"""
app/main.py
수집기 FastAPI 애플리케이션.

[역할 분담]
  수집기(Python)  : 외부 소스 수집·파싱·적재, 그리고 키가 필요한 실시간 조회
  백엔드(Java)    : 저장본 읽기·분석·캐시·인증·화면용 API
  프런트(Next.js) : 표시

구버전의 `python collector.py --loop` / `--status` / `--task` / `--verify`는
각각 스케줄러와 아래 엔드포인트로 옮겼습니다.

  --loop            → 상주 스케줄러 (COLLECTOR_SCHEDULER=true)
  --only fast       → POST /collect?group=fast
  --task krx_futures→ POST /collect/task/krx_futures
  --status          → GET  /status
  --history         → GET  /task-history
  --verify          → GET  /verify/* (판정은 백엔드가 합니다)
  --purge-days      → POST /maintenance/purge?days=400
"""
from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import BackgroundTasks, FastAPI, Header, HTTPException, Query

from . import catalog, indicators, settings, store, tasks
from .services import (
    kis as kis_service,
    krx as krx_service,
    ls as ls_service,
    market as market_service,
    radar as radar_service,
    toss as toss_service,
)

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("collector")

KST = ZoneInfo("Asia/Seoul")

scheduler: BackgroundScheduler | None = None

# 내부 서비스 간 호출용 토큰. 설정하지 않으면 검사하지 않습니다
# (로컬 개발 편의). 운영에서는 반드시 설정하세요.
API_TOKEN = os.environ.get("COLLECTOR_API_TOKEN", "")


@asynccontextmanager
async def lifespan(app: FastAPI):
    store.get_pool()
    if os.environ.get("COLLECTOR_INIT_SCHEMA", "true").lower() in ("1", "true", "yes"):
        store.init_schema()

    # 비정상 종료로 'running'에 남아 있던 기록을 먼저 정리합니다.
    try:
        store.mark_stale_runs_interrupted()
    except Exception as exc:  # noqa: BLE001
        logger.warning("오래된 실행 기록 정리 실패: %s", exc)

    global scheduler
    if settings.scheduler_enabled():
        scheduler = BackgroundScheduler(timezone="UTC")
        for group in ("fast", "slow", "weekly"):
            seconds = settings.interval_seconds(group)
            scheduler.add_job(
                _run_group_job,
                "interval",
                seconds=seconds,
                args=[group],
                id=f"collect-{group}",
                max_instances=1,
                coalesce=True,
                # 기동 직후 한 번씩 돌립니다. 싼 것부터(fast → slow → weekly)
                # 처리해야 가장 자주 보는 데이터가 먼저 채워집니다.
                next_run_time=datetime.now(tz=ZoneInfo("UTC")),
            )
            logger.info("스케줄 등록: %s 주기 %d초", group, seconds)
        scheduler.start()
    else:
        logger.info("스케줄러 비활성화 — REST 요청으로만 수집합니다.")

    yield

    if scheduler:
        scheduler.shutdown(wait=False)
    store.close_pool()


app = FastAPI(
    title="Local Macro Dashboard — Collector",
    version="2.0.0",
    description="외부 시장 데이터 수집기 (PostgreSQL 적재 + 실시간 조회 API)",
    lifespan=lifespan,
)


def _run_group_job(group: str) -> None:
    try:
        tasks.run_group(group)
    except Exception as exc:  # noqa: BLE001
        logger.exception("스케줄 수집 실패 (%s): %s", group, exc)


def _check_token(token: str | None) -> None:
    if API_TOKEN and token != API_TOKEN:
        raise HTTPException(status_code=401, detail="유효하지 않은 서비스 토큰입니다.")


# ==============================================================================
# 상태
# ==============================================================================
@app.get("/health")
def health() -> dict:
    return {"status": "ok", "schedulerEnabled": settings.scheduler_enabled()}


@app.get("/status")
def status() -> dict:
    """구버전 `collector.py --status`에 해당합니다."""
    stats = store.store_stats()
    stats["keys"] = {
        "fred": bool(settings.fred_key()),
        "krx": bool(settings.krx_key()),
        "kis": kis_service.has_credentials(),
        "ls": ls_service.has_credentials(),
        "toss": toss_service.has_credentials(),
    }
    stats["intervals"] = {
        group: settings.interval_seconds(group)
        for group in ("fast", "slow", "weekly")
    }
    return stats


@app.get("/tasks")
def list_tasks() -> dict:
    return {
        "tasks": [
            {"name": t.name, "speed": t.speed, "description": t.description}
            for t in tasks.ALL_TASKS
        ]
    }


@app.get("/task-history")
def task_history(
    task: str | None = None,
    limit: int = Query(40, ge=1, le=200),
) -> dict:
    rows = store.read_task_history(task, limit)
    return {"history": [store._serialize_task(row) for row in rows]}


# ==============================================================================
# 수집 실행
# ==============================================================================
@app.post("/collect")
def collect(
    group: str = Query("all", pattern="^(fast|slow|weekly|all)$"),
    wait: bool = Query(True, description="false면 백그라운드로 실행하고 즉시 응답"),
    background: BackgroundTasks = None,          # type: ignore[assignment]
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)

    if wait:
        return tasks.run_group(group)

    background.add_task(tasks.run_group, group)
    return {"group": group, "accepted": True}


@app.post("/collect/task/{task_name}")
def collect_task(
    task_name: str,
    wait: bool = Query(True, description="false면 백그라운드로 실행하고 즉시 응답"),
    background: BackgroundTasks = None,          # type: ignore[assignment]
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    태스크 1건을 실행합니다.

    wait=false는 **화면이 수집을 기다리지 않게** 하려고 있습니다. 저장본이
    이미 있는데 조금 오래된 경우, 백엔드가 이 호출이 끝나기를 기다리면 화면이
    그만큼 멈춥니다. 실제로 sec_13f 한 건에 31.8초, fred_series에 11.5초
    동안 페이지가 붙잡혔습니다.
    """
    _check_token(x_service_token)
    if task_name not in tasks.TASKS_BY_NAME:
        raise HTTPException(
            status_code=404,
            detail=f"알 수 없는 태스크: {task_name} (가능: {sorted(tasks.TASKS_BY_NAME)})",
        )

    if not wait:
        background.add_task(tasks.run_group, task_name=task_name)
        return {"task": task_name, "accepted": True}

    return tasks.run_group(task_name=task_name)


@app.post("/refresh")
def refresh(
    scope: str = "global",
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    수동 새로고침 기준 시각을 갱신합니다.

    저장본을 지우지 않습니다 — 수집이 실패하면 보여 줄 값이 아예 없어지기
    때문입니다. "이 시각 이전 저장본은 낡은 것으로 본다"는 기준만 세웁니다.
    """
    _check_token(x_service_token)
    requested_at = store.request_refresh(scope)
    return {"scope": scope, "requestedAt": requested_at.isoformat()}


@app.post("/maintenance/purge")
def purge(
    days: int = Query(400, ge=30),
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    return store.purge_older_than(days)


# ==============================================================================
# 실시간 조회 (백엔드가 auto 모드에서 호출)
# ==============================================================================
@app.get("/live/radar")
def live_radar(
    market: str = "KOSPI",
    investor: str = "외국인",
    tradeType: str = "순매수",
    topN: int = Query(30, ge=5, le=100),
    intervalType: str = Query("TODAY", pattern="^(TODAY|DAYS_5|DAYS_20)$"),
    targetDate: str | None = None,
) -> dict:
    """
    수급 랭킹을 지금 수집합니다 (폴백 체인 전체를 탑니다).

    저장도 함께 합니다. 화면이 기다린 수집 결과를 버리면 다음 사용자가 또
    기다리게 되기 때문입니다.
    """
    day = date.fromisoformat(targetDate) if targetDate else datetime.now(KST).date()
    result = radar_service.collect_radar_ranking(
        day, market, investor, tradeType, topN, intervalType
    )

    if result.get("rows"):
        store.put_snapshot(
            catalog.snap_radar_scanner(market, investor, tradeType, intervalType),
            result,
        )
        if not result.get("isHistorical"):
            radar_service.accumulate_history(
                result["rows"], market, investor, tradeType, intervalType
            )
    return result


@app.get("/live/ticker/{symbol:path}")
def live_ticker(symbol: str, period: str = "1mo") -> dict:
    """저장 대상이 아닌 개별 티커 차트(단일 지표 조회 화면)용."""
    return market_service.collect_ticker(symbol, period)


@app.get("/live/daum-intraday")
def live_daum_intraday(minutes: int = Query(30, ge=5, le=180)) -> dict:
    """장중 선물 수급 가속도. 1분 단위로 변하므로 저장하지 않습니다."""
    return krx_service.collect_daum_intraday_acceleration(minutes)


@app.get("/live/radar-history-dates")
def radar_history_dates() -> dict:
    return {"dates": store.list_observation_dates(catalog.OBS_RADAR)}


@app.get("/live/radar-history")
def radar_history(
    market: str | None = None,
    investor: str | None = None,
    tradeType: str | None = None,
    obsDate: str | None = None,
    startDate: str | None = None,
) -> dict:
    filters: dict[str, str] = {}
    if market:
        filters["market"] = market
    if investor:
        filters["investor"] = investor
    if tradeType:
        filters["tradeType"] = tradeType

    return {
        "rows": store.read_observations(
            catalog.OBS_RADAR,
            obs_date=obsDate,
            start_date=startDate,
            filters=filters or None,
        )
    }


# ==============================================================================
# 교차 검증용 읽기 (판정은 백엔드가 합니다)
# ==============================================================================
@app.get("/verify/readings")
def verification_readings(
    market: str = "KOSPI",
    investor: str = "외국인",
    tradeType: str = "순매수",
) -> dict:
    """
    같은 수치를 서로 다른 출처에서 읽어 **원자료 그대로** 돌려줍니다.

    일치/불일치 판정은 백엔드(Java)의 VerificationService가 합니다. 판정
    규칙(허용 오차, 장 시간 게이트, "확인 못 함"과 "일치"를 섞지 않기)을 한
    곳에 모아 두기 위해서입니다.
    """
    now = datetime.now(KST)
    top_row = _top_ranking_row(market, investor, tradeType, now)

    return {
        "checkedAt": now.isoformat(),
        "keys": {
            "krx": bool(settings.krx_key()),
            "kis": kis_service.has_credentials(),
        },
        "kisFutures": kis_service.fetch_kospi200_futures(),
        "kisIndex": kis_service.fetch_index_close(),
        "krxIndex": _krx_index_reading(),
        "yfinanceIndex": _yfinance_index_reading(),
        "rankingTop": top_row,
    }


def _krx_index_reading() -> dict:
    """확정치는 하루 지연될 수 있어 최근 영업일을 며칠 거슬러 봅니다."""
    from datetime import timedelta

    if not settings.krx_key():
        return {"ok": False, "value": None, "detail": "KRX api_key가 없습니다."}

    today = datetime.now(KST).date()
    for back in range(0, 7):
        day = today - timedelta(days=back)
        value = krx_service.fetch_kospi200_index_close(day.strftime("%Y%m%d"))
        if value:
            # asOf는 판정에 쓰입니다(백엔드가 기준일이 다른 값을 비교하지 않도록).
            # detail은 사람이 읽는 문구, asOf는 기계가 읽는 날짜로 나눠 둡니다.
            return {
                "ok": True,
                "value": float(value),
                "asOf": day.isoformat(),
                "detail": f"기준일 {day.isoformat()}",
            }
    return {"ok": False, "value": None, "detail": "최근 7일 안에 확정 지수가 없습니다."}


def _yfinance_index_reading() -> dict:
    """제3의 참고 출처. 공식은 아니지만 두 공식 출처가 갈릴 때 표를 던집니다."""
    payload = market_service.collect_ticker("^KS200", "5d")
    points = [p for p in payload.get("points", []) if p.get("close")]
    if not points:
        return {
            "ok": False,
            "value": None,
            "detail": f"^KS200 조회 실패{_reason(payload)}",
        }

    last = points[-1]
    as_of = str(last.get("date") or "")[:10] or None
    return {
        "ok": True,
        "value": float(last["close"]),
        "asOf": as_of,
        "detail": f"^KS200 (참고{', 기준일 ' + as_of if as_of else ''})",
    }


def _reason(payload: dict) -> str:
    error = payload.get("error")
    return f" — {error}" if error else ""


def _top_ranking_row(market: str, investor: str, trade_type: str, now: datetime) -> dict:
    """
    KIS 가집계와 Daum이 말하는 '1위 종목'을 각각 읽습니다.

    ⚠️ 가격·지수와 시간 조건이 **정반대**입니다. KIS 가집계 TR은 장중 전용이라
    마감 후에는 빈 데이터가 정상입니다. 그래서 이 대조는 정규장에만 가능합니다.
    """
    date_str = now.strftime("%Y%m%d")

    def read(fetch, label: str) -> dict:
        try:
            rows = fetch(date_str, market, investor, trade_type, 10)
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "source": label, "detail": str(exc)[:200]}
        if not rows:
            return {"ok": False, "source": label, "detail": "빈 결과"}
        top = rows[0]
        return {
            "ok": True,
            "source": label,
            "name": top.get("name"),
            "code": top.get("code"),
            "value": top.get("netAmountEok"),
        }

    return {
        "isRegularSession": radar_service.is_regular_session(now),
        "kis": read(kis_service.fetch_deal_ranking, "KIS 장중 가집계"),
        "daum": read(
            lambda d, m, i, t, n: radar_service.fetch_daum_ranking(d, m, i, t, n, "TODAY"),
            "Daum (화면이 쓰는 값)",
        ),
    }


# ==============================================================================
# 연결 진단
# ==============================================================================
@app.get("/diagnostics/connections")
def diagnostics() -> dict:
    """
    5개 데이터 소스 + 토스의 연결 상태.

    진단은 **화면이 실제로 쓰는 경로**를 그대로 호출합니다. 진단이 다른
    경로를 보면 "진단은 정상인데 화면은 빈" 상황을 설명할 수 없습니다.
    """
    return {
        "checkedAt": datetime.now(KST).isoformat(),
        "sources": {
            "kis": kis_service.test_connection(),
            "ls": ls_service.test_connection(),
            "daum": radar_service.test_daum_connection(),
            "naver": radar_service.test_naver_connection(),
            "pykrx": radar_service.test_pykrx_connection(),
        },
    }


@app.get("/diagnostics/toss")
def toss_diagnostics() -> dict:
    return toss_service.test_connection()


@app.get("/toss/exchange-rate")
def toss_exchange_rate(base: str = "USD", quote: str = "KRW") -> dict:
    return toss_service.get_exchange_rate(base, quote)


@app.get("/toss/indices")
def toss_indices(symbols: str = Query(..., description="쉼표로 구분")) -> dict:
    return toss_service.get_index_prices([s.strip() for s in symbols.split(",") if s.strip()])


# ==============================================================================
# 카탈로그 (백엔드/프런트가 같은 정의를 쓰도록 노출)
# ==============================================================================
@app.get("/catalog")
def catalog_info() -> dict[str, Any]:
    return {
        "macroCategories": indicators.MACRO_CATEGORIES,
        "institutions": indicators.INSTITUTIONS,
        "sectorEtfs": indicators.SECTOR_ETFS,
        "assetClassEtfs": indicators.ASSET_CLASS_ETFS,
        "cotAssets": indicators.COT_ASSETS,
        "fredSeries": {
            "base": list(indicators.FRED_BASE_SERIES),
            "advanced": list(indicators.FRED_ADVANCED_SERIES),
        },
        "snapshots": {
            "macro": catalog.SNAP_MACRO_COLLECTED,
            "scraper": catalog.SNAP_SCRAPER_MARKETS,
            "liquidity": catalog.SNAP_FED_LIQUIDITY,
            "krxFutures": catalog.SNAP_KRX_FUTURES,
            "sector": catalog.SNAP_SECTOR_HISTORY,
            "cot": catalog.SNAP_COT_HISTORY,
        },
    }
