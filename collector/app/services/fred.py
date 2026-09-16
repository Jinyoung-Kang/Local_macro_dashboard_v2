"""
app/services/fred.py
FRED 시계열 수집 (공식 API → 웹 CSV 폴백).

수집에 실패하면 **가짜 값을 만들지 않고 빈 결과**를 돌려줍니다.
구버전 services/macro_service.collect_fred_series()와 같은 원칙입니다.
(연준 순유동성만 예외적으로 '추정치' 경로가 있는데, 그쪽은 반드시
is_estimated 플래그를 달아 화면이 경고할 수 있게 합니다.)
"""
from __future__ import annotations

import csv
import io
import logging
from datetime import datetime, timedelta

from .. import settings
from ..http import get_fred_session

logger = logging.getLogger(__name__)


def collect_series(series_id: str, period_years: int = 10) -> list[dict]:
    """
    FRED 시계열 1종을 수집합니다.

    반환: [{"date": "YYYY-MM-DD", "value": float}, ...] (오름차순)
          실패하면 빈 리스트.
    """
    start_date = (
        datetime.now() - timedelta(days=period_years * 365 + 90)
    ).strftime("%Y-%m-%d")

    points = _from_api(series_id, start_date)
    if len(points) >= 2:
        return points

    points = _from_csv(series_id)
    if len(points) >= 2:
        return points

    logger.error(
        "%s: FRED API와 CSV가 모두 실패했습니다. "
        "가짜 데이터를 만들지 않고 빈 결과를 반환합니다.",
        series_id,
    )
    return []


def _from_api(series_id: str, start_date: str) -> list[dict]:
    key = settings.fred_key()
    if not key:
        logger.info("%s: FRED API 키가 없어 웹 CSV 경로로 진행합니다.", series_id)
        return []

    url = f"{settings.FRED_API_BASE}/series/observations"
    params = {
        "series_id": series_id,
        "api_key": key,
        "file_type": "json",
        "observation_start": start_date,
    }
    try:
        res = get_fred_session().get(url, params=params, timeout=15)
    except Exception as exc:  # noqa: BLE001
        logger.warning("FRED API 실패 (%s): %s", series_id, exc)
        return []

    if res.status_code != 200:
        logger.warning(
            "FRED API 응답 실패 (%s): HTTP %s - %s",
            series_id, res.status_code, res.text[:200],
        )
        return []

    try:
        observations = res.json().get("observations", [])
    except ValueError as exc:
        logger.warning("FRED API JSON 해석 실패 (%s): %s", series_id, exc)
        return []

    return _clean_points(
        (row.get("date"), row.get("value")) for row in observations
    )


def _from_csv(series_id: str) -> list[dict]:
    """
    FRED 웹 CSV 폴백.

    FRED가 날짜 컬럼명을 'DATE'에서 'observation_date'로 바꾼 이력이 있어,
    컬럼명을 고정하지 않고 탐색합니다.
    """
    try:
        res = get_fred_session().get(
            settings.FRED_CSV_BASE, params={"id": series_id}, timeout=20
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("FRED CSV 다운로드 실패 (%s): %s", series_id, exc)
        return []

    if res.status_code != 200 or len(res.text) < 30:
        logger.warning(
            "FRED CSV 응답 비정상 (%s): HTTP %s", series_id, res.status_code
        )
        return []

    reader = csv.DictReader(io.StringIO(res.text))
    fieldnames = reader.fieldnames or []
    date_col = next(
        (c for c in ("observation_date", "DATE", "date") if c in fieldnames), None
    )
    if date_col is None:
        logger.warning(
            "FRED CSV에서 날짜 컬럼을 찾지 못했습니다 (%s): %s", series_id, fieldnames
        )
        return []

    value_col = next((c for c in fieldnames if c != date_col), None)
    if value_col is None:
        return []

    return _clean_points(
        (row.get(date_col), row.get(value_col)) for row in reader
    )


def _clean_points(rows) -> list[dict]:
    """'.'(결측)과 파싱 불가 값을 버리고 날짜 오름차순으로 정리합니다."""
    out: list[dict] = []
    for raw_date, raw_value in rows:
        if not raw_date or raw_value in (None, "", "."):
            continue
        try:
            value = float(str(raw_value).strip())
        except (TypeError, ValueError):
            continue
        if value != value:      # NaN
            continue
        out.append({"date": str(raw_date)[:10], "value": value})

    out.sort(key=lambda p: p["date"])
    return out


def latest_value(points: list[dict]) -> float | None:
    """마지막 값. FRED는 하루 지연이므로 이 값이 곧 직전 거래일 확정치입니다."""
    if not points:
        return None
    value = points[-1]["value"]
    return float(value) if value is not None else None
