"""
app/http.py
공용 HTTP 세션 (커넥션 풀 + 재시도).

구버전 services/http_client.py와 같은 목적입니다. 요청마다 새 Session을 만들면
TCP/TLS 핸드셰이크를 매번 반복하고, 레이트리밋 대응(재시도)도 제각각이 됩니다.
"""
from __future__ import annotations

import logging
import threading
import time

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

logger = logging.getLogger(__name__)

BROWSER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
}

# SEC는 연락처가 포함된 User-Agent를 요구합니다(미준수 시 403).
SEC_HEADERS = {
    "User-Agent": "LocalMacroDashboard/2.0 (contact: research@example.com)",
    "Accept-Encoding": "gzip, deflate",
}

_lock = threading.Lock()
_sessions: dict[str, requests.Session] = {}


def _build_session(
    name: str,
    *,
    headers: dict,
    total_retries: int,
    backoff: float,
    pool_size: int,
) -> requests.Session:
    session = requests.Session()
    retry = Retry(
        total=total_retries,
        backoff_factor=backoff,
        status_forcelist=(403, 408, 429, 500, 502, 503, 504),
        allowed_methods=("GET", "POST"),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(
        max_retries=retry,
        pool_connections=pool_size,
        pool_maxsize=pool_size,
    )
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update(headers)
    logger.debug("HTTP 세션 생성: %s", name)
    return session


def get_session() -> requests.Session:
    """일반 웹 소스(Daum/Naver/TradingView/CFTC)용 공용 세션."""
    return _get("default", headers=BROWSER_HEADERS, retries=2, backoff=0.5, pool=16)


def get_fred_session() -> requests.Session:
    """
    FRED 전용 세션.

    FRED 웹 CSV는 기본 User-Agent로 요청하면 403을 주는 경우가 있어
    브라우저 헤더를 붙입니다.
    """
    return _get("fred", headers=BROWSER_HEADERS, retries=3, backoff=0.8, pool=8)


def get_sec_session() -> requests.Session:
    """SEC EDGAR 전용 세션 (연락처 포함 UA + 넉넉한 재시도)."""
    return _get("sec", headers=SEC_HEADERS, retries=5, backoff=1.5, pool=10)


def _get(
    name: str, *, headers: dict, retries: int, backoff: float, pool: int
) -> requests.Session:
    with _lock:
        session = _sessions.get(name)
        if session is None:
            session = _build_session(
                name,
                headers=headers,
                total_retries=retries,
                backoff=backoff,
                pool_size=pool,
            )
            _sessions[name] = session
        return session


def close_all() -> None:
    with _lock:
        for session in _sessions.values():
            session.close()
        _sessions.clear()


# ==============================================================================
# SEC 레이트 리미터 (초당 요청 한도 준수 + 병렬 수집 허용)
# ==============================================================================
# SEC EDGAR는 초당 10요청을 넘기면 차단합니다. 요청 사이에 sleep을 넣는 방식은
# 호출을 **직렬화**해서 기관 12곳 × 8분기 = 약 200요청이 한 줄로 늘어섭니다.
# 토큰 버킷은 "전체 합계가 초당 N건"만 지키므로 병렬 수집이 가능합니다.
# 한도는 10이 아니라 8로 둡니다(버스트·시계 오차 여유분).
_SEC_MAX_RPS = 8.0
_sec_lock = threading.Lock()
_sec_next_slot = [0.0]


def sec_rate_limit() -> None:
    interval = 1.0 / _SEC_MAX_RPS
    with _sec_lock:
        now = time.monotonic()
        slot = max(now, _sec_next_slot[0])
        _sec_next_slot[0] = slot + interval
    wait = slot - time.monotonic()
    if wait > 0:
        time.sleep(wait)
