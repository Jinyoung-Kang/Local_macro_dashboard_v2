"""
app/services/publicprobe.py
국내 공공 API 연결 진단 — API마다 가장 가벼운 호출 한 번.

화면의 "연결 진단" 버튼이 부릅니다. 목적은 **키를 넣은 뒤 무엇이 되고 무엇이 안
되는지**를 한눈에 보는 것입니다. 활용신청은 서비스마다 따로라, 키는 맞는데 특정
서비스만 승인이 안 된 경우가 흔합니다.

주의사항
  - 결과에는 키를 싣지 않습니다. 설정 여부(bool)와 키가 지워진 사유만 돌려줍니다.
  - 수집(저장)은 하지 않습니다. 한 번 누를 때 API마다 1회씩, 모두 3회를 씁니다.
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from .. import publicapi, settings
from . import dart, fsc, kasi

KST = ZoneInfo("Asia/Seoul")

# 진단용 표본. 삼성전자 DART 고유번호(공식 가이드 예제와 오픈소스 테스트에서 쓰는 값).
_SAMPLE_CORP_CODE = "00126380"


def _run(label: str, configured: bool, call) -> dict:
    """호출 하나를 감싸 결과·소요 시간·사유를 한 형태로."""
    out = {"label": label, "configured": configured, "ok": False, "detail": None, "elapsedMs": None}
    if not configured:
        out["detail"] = "키 미설정"
        return out
    started = time.monotonic()
    try:
        out["detail"] = call()
        out["ok"] = True
    except publicapi.PublicApiError as exc:
        out["detail"] = str(exc)  # 이미 키가 지워진 문구
    except Exception as exc:  # noqa: BLE001 — 진단은 어떤 실패든 한 줄로 보여 줘야 합니다
        out["detail"] = publicapi.scrub(f"{type(exc).__name__}: {exc}",
                                        settings.data_go_kr_key(), settings.dart_key())
    out["elapsedMs"] = int((time.monotonic() - started) * 1000)
    return out


def _kasi() -> str:
    year = datetime.now(KST).year
    return f"{year}년 공휴일 {len(kasi.fetch_year(year))}건"


def _fsc() -> str:
    # 오늘은 발표 전이므로 가장 가까운 지난 평일 하나만 확인합니다(1회).
    day = datetime.now(KST).date() - timedelta(days=1)
    while day.weekday() >= 5:
        day -= timedelta(days=1)
    key = settings.data_go_kr_key()
    url = fsc.endpoint()
    response = publicapi.get(url, {"basDt": day.strftime("%Y%m%d"), "numOfRows": 1, "pageNo": 1}, key=key)
    root = publicapi.parse_xml(response, key=key)
    total = publicapi.total_count(root)
    # 공휴일이면 0종목이 정상입니다(휴장일에는 시세가 없음).
    return f"{day} 기준 {total if total is not None else '?'}종목 ({url.rsplit('/', 1)[-1]})"


def _dart() -> str:
    key = settings.dart_key()
    response = publicapi.get(f"{dart.BASE}/company.json", {"corp_code": _SAMPLE_CORP_CODE},
                              key=key, key_param="crtfc_key")
    body = dart._json(response, key)
    return f"기업개황 조회 성공 ({body.get('stock_name') or body.get('corp_name') or '이름 없음'})"


def run() -> dict:
    """세 API를 차례로 한 번씩 확인합니다."""
    has_portal = bool(settings.data_go_kr_key())
    return {
        "checkedAt": datetime.now(KST).isoformat(),
        "keys": {"DATA_GO_KR_SERVICE_KEY": has_portal, "DART_API_KEY": bool(settings.dart_key())},
        "apis": [
            _run("한국천문연구원 특일정보", has_portal, _kasi),
            _run("금융위원회 주식시세정보", has_portal, _fsc),
            _run("금융감독원 Open DART", bool(settings.dart_key()), _dart),
        ],
    }
