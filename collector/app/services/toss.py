"""
app/services/toss.py
토스증권 Open API 연결 진단.

이 프로젝트에서 토스는 **연결 테스트 메뉴 전용**입니다. 대시보드 수치는
토스에서 가져오지 않습니다(구버전과 동일). 키가 없으면 메뉴만 비활성화됩니다.
"""
from __future__ import annotations

import logging
import threading
import time

import requests

from .. import settings

logger = logging.getLogger(__name__)

BASE_URL = "https://openapi.tossinvest.com"
AUTH_URL = f"{BASE_URL}/oauth2/token"

_lock = threading.Lock()
_token_cache: tuple[str, float] | None = None


def has_credentials() -> bool:
    client_id, client_secret = settings.toss_credentials()
    return bool(client_id and client_secret)


def get_access_token() -> tuple[str | None, str | None]:
    """(토큰, 오류 메시지). 성공한 토큰만 캐시합니다."""
    global _token_cache

    with _lock:
        if _token_cache and _token_cache[1] > time.time():
            return _token_cache[0], None

    client_id, client_secret = settings.toss_credentials()
    if not client_id or not client_secret:
        return None, "[toss] client_id / client_secret이 설정되지 않았습니다."

    try:
        res = requests.post(
            AUTH_URL,
            data={
                "grant_type": "client_credentials",
                "client_id": client_id,
                "client_secret": client_secret,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=8,
        )
        res.raise_for_status()
        payload = res.json()
    except requests.HTTPError as exc:
        status = exc.response.status_code if exc.response is not None else "?"
        body = exc.response.text[:300] if exc.response is not None else ""
        return None, f"HTTP {status} 오류: {body}"
    except Exception as exc:  # noqa: BLE001
        return None, f"토큰 발급 실패: {exc}"

    token = payload.get("access_token")
    if not token:
        return None, f"토큰 응답에 access_token이 없습니다: {payload}"

    expires_in = int(payload.get("expires_in", 3600))
    with _lock:
        _token_cache = (token, time.time() + max(60, expires_in - 60))
    return token, None


def test_connection() -> dict:
    token, error = get_access_token()
    if not token:
        return {"ok": False, "stage": "token", "message": error}

    try:
        res = requests.get(
            f"{BASE_URL}/api/v1/exchange-rate",
            headers={"Authorization": f"Bearer {token}"},
            params={"baseCurrency": "USD", "quoteCurrency": "KRW"},
            timeout=8,
        )
    except requests.Timeout:
        return {"ok": False, "stage": "network", "message": "요청 시간 초과(8초)"}
    except requests.ConnectionError as exc:
        return {"ok": False, "stage": "network", "message": f"연결 실패: {exc}"}

    if res.status_code == 403:
        return {
            "ok": False, "stage": "forbidden",
            "message": "HTTP 403 — 허용 IP 목록에 현재 IP가 없을 수 있습니다.",
        }
    if res.status_code == 400:
        return {
            "ok": False, "stage": "bad_request",
            "message": f"HTTP 400 — 요청 파라미터 오류: {res.text[:200]}",
        }
    if res.status_code != 200:
        return {
            "ok": False, "stage": "http_error",
            "message": f"HTTP {res.status_code}: {res.text[:200]}",
        }

    try:
        data = res.json()
    except ValueError:
        return {"ok": False, "stage": "bad_response", "message": "JSON 해석 실패"}

    return {
        "ok": True, "stage": "ok",
        "message": "연결 성공",
        "sample": data,
    }


def get_exchange_rate(base: str = "USD", quote: str = "KRW") -> dict:
    token, error = get_access_token()
    if not token:
        return {"ok": False, "error": error}

    try:
        res = requests.get(
            f"{BASE_URL}/api/v1/exchange-rate",
            headers={"Authorization": f"Bearer {token}"},
            params={"baseCurrency": base, "quoteCurrency": quote},
            timeout=8,
        )
        res.raise_for_status()
        return {"ok": True, "data": res.json()}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)[:300]}


def get_index_prices(symbols: list[str]) -> dict:
    token, error = get_access_token()
    if not token:
        return {"ok": False, "error": error}

    try:
        res = requests.get(
            f"{BASE_URL}/api/v1/market-indicators/prices",
            headers={"Authorization": f"Bearer {token}"},
            params={"symbols": ",".join(symbols)},
            timeout=8,
        )
        res.raise_for_status()
        return {"ok": True, "data": res.json()}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)[:300]}
