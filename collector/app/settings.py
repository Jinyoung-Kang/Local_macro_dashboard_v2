"""
app/settings.py
수집기 설정과 외부 API 키 로더.

구버전(config.py)은 Streamlit Secrets(.streamlit/secrets.toml)를 읽었습니다.
지금은 Streamlit이 없으므로 **환경변수 + 선택적 TOML 파일** 두 경로만 씁니다.
키가 없어도 수집기는 정상 기동해야 하며, 키가 필요한 소스만 건너뜁니다.
(구버전 README의 "키가 없을 때의 동작" 표를 그대로 유지합니다.)
"""
from __future__ import annotations

import logging
import os
import tomllib
from functools import lru_cache
from pathlib import Path

logger = logging.getLogger(__name__)

# secrets.toml 경로. 없으면 환경변수만으로 동작합니다.
_SECRETS_PATH = Path(
    os.environ.get("MACRO_SECRETS_FILE", "/run/secrets/secrets.toml")
)


@lru_cache(maxsize=1)
def _load_secrets_file() -> dict:
    """secrets.toml을 한 번만 읽습니다. 없거나 깨졌으면 빈 dict."""
    if not _SECRETS_PATH.exists():
        return {}
    try:
        with _SECRETS_PATH.open("rb") as fp:
            return tomllib.load(fp)
    except Exception as exc:  # noqa: BLE001
        logger.warning("secrets 파일을 읽지 못했습니다 (%s): %s", _SECRETS_PATH, exc)
        return {}


def get_secret(key_path: str, default: str = "") -> str:
    """
    'fred.api_key' 같은 점 표기로 값을 찾습니다.

    탐색 순서
      1) 환경변수 FRED_API_KEY (점 → 밑줄, 대문자)
      2) 환경변수 key_path 그대로
      3) secrets.toml의 [fred] api_key

    구버전은 Streamlit Secrets를 먼저 봤지만, 컨테이너 환경에서는 환경변수가
    1차 수단이므로 순서를 뒤집었습니다.
    """
    env_name = key_path.replace(".", "_").upper()
    for candidate in (env_name, key_path):
        value = os.environ.get(candidate)
        if value and value.strip():
            return value.strip()

    node: object = _load_secrets_file()
    for part in key_path.split("."):
        if isinstance(node, dict) and part in node:
            node = node[part]
        else:
            return default

    return str(node).strip() if node is not None else default


# ==============================================================================
# 데이터베이스 / 서비스 설정
# ==============================================================================
def database_url() -> str:
    """psycopg 연결 문자열."""
    return os.environ.get(
        "DATABASE_URL",
        "postgresql://macro:macro@localhost:5432/macrodash",
    )


def scheduler_enabled() -> bool:
    """
    상주 스케줄러 사용 여부.

    구버전의 `python collector.py --loop`에 해당합니다. 끄면 수집기는
    REST 요청(POST /collect)으로만 동작합니다.
    """
    return os.environ.get("COLLECTOR_SCHEDULER", "true").lower() in (
        "1", "true", "yes", "on",
    )


def interval_seconds(group: str) -> int:
    """작업군별 수집 주기. 구버전 기본값(5분 / 1시간 / 12시간)과 같습니다."""
    defaults = {"fast": 5 * 60, "slow": 60 * 60, "weekly": 12 * 60 * 60}
    env_key = f"COLLECTOR_{group.upper()}_INTERVAL"
    try:
        return max(30, int(os.environ.get(env_key, defaults[group])))
    except (TypeError, ValueError):
        return defaults[group]


# ==============================================================================
# 외부 API 키 (없으면 빈 문자열)
# ==============================================================================
def fred_key() -> str:
    return get_secret("fred.api_key") or get_secret("FRED_API_KEY")


def krx_key() -> str:
    return (
        get_secret("krx.api_key")
        or get_secret("krx.auth_key")
        or get_secret("KRX_AUTH_KEY")
    )


def kis_credentials() -> tuple[str, str]:
    return get_secret("kis.app_key"), get_secret("kis.app_secret")


def ls_credentials() -> tuple[str, str]:
    return get_secret("ls.app_key"), get_secret("ls.app_secret")


def ls_base_url_override() -> str:
    return get_secret("ls.base_url")


def toss_credentials() -> tuple[str, str]:
    return get_secret("toss.client_id"), get_secret("toss.client_secret")


KRX_BASE_URL = "https://data-dbg.krx.co.kr/svc/apis"
FRED_API_BASE = "https://api.stlouisfed.org/fred"
FRED_CSV_BASE = "https://fred.stlouisfed.org/graph/fredgraph.csv"
KIS_BASE_URL = "https://openapi.koreainvestment.com:9443"

# LS OPEN API 주소.
# 문서에는 오랫동안 :8080이 적혀 있었지만 서버가 그 포트를 더 이상 열어두지
# 않습니다(즉시 connection refused). 표준 443을 먼저 쓰고 8080은 보조로만
# 남깁니다. 구버전 README의 "포트 주의" 항목과 같은 판단입니다.
LS_BASE_URL = "https://openapi.ls-sec.co.kr"
LS_ALT_BASE_URLS = ("https://openapi.ls-sec.co.kr:8080",)
