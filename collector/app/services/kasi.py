"""
app/services/kasi.py
한국천문연구원 특일정보 — 공휴일(getRestDeInfo).

왜 필요한가 — 거래소 개장 여부를 화면이 직접 계산하는데, 고정 규칙으로는
대체공휴일·선거일·임시공휴일을 알 수 없습니다. 실제 데이터와 대조해 보니
2025~2027년에만 평일 공휴일 14일을 놓치고 있었습니다
(예: 2026-08-17 대체공휴일(광복절), 2026-06-03 전국동시지방선거).

응답 구조(관측): response/header/resultCode=00, body/items/item 에
dateKind·dateName·isHoliday(Y/N)·locdate(YYYYMMDD)·seq, body/totalCount.
아직 발표되지 않은 해는 resultCode 00 + totalCount 0 으로 옵니다.
"""
from __future__ import annotations

import re

from .. import publicapi, settings

URL = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo"

# 한 해 공휴일은 많아야 30건 안팎입니다. 한 페이지로 받아 페이지 처리를 피합니다.
_ROWS = 100
_LOCDATE = re.compile(r"^\d{8}$")


def fetch_year(year: int) -> list[dict]:
    """
    그 해의 공휴일(isHoliday=Y) 목록.

    :param year: 양력 연도
    :returns: ``[{"date": "YYYY-MM-DD", "name": "설날"}, ...]`` 날짜순.
              아직 발표되지 않은 해는 빈 목록
    :raises publicapi.MissingKey: DATA_GO_KR_SERVICE_KEY 미설정
    :raises publicapi.PublicApiError: 인증·서비스 오류, 형식 이상
    """
    key = settings.data_go_kr_key()
    response = publicapi.get(
        URL, {"solYear": year, "numOfRows": _ROWS, "pageNo": 1}, key=key,
    )
    root = publicapi.parse_xml(response, key=key)
    return parse_holidays(publicapi.xml_items(root), publicapi.total_count(root))


def parse_holidays(items: list[dict], total: int | None) -> list[dict]:
    """
    응답 항목을 공휴일 목록으로 옮깁니다.

    주의사항
      - 같은 날짜가 두 번 올 수 있습니다(2025-05-05 어린이날·부처님오신날).
        휴장 여부만 보면 되므로 이름을 ``·``로 합쳐 한 건으로 만듭니다.
      - totalCount와 받은 건수가 다르면 페이지가 잘린 것이므로 실패로 봅니다.
        일부만 저장하면 빠진 날이 "거래일"로 보입니다.
      - seq는 쓰지 않습니다. 의미가 문서화돼 있지 않습니다.
    """
    if total is not None and total != len(items):
        raise publicapi.PublicApiError(
            f"totalCount({total})와 받은 항목 수({len(items)})가 다릅니다 — 페이지가 잘렸을 수 있습니다"
        )

    by_date: dict[str, list[str]] = {}
    for item in items:
        if item.get("isHoliday", "").upper() != "Y":
            continue
        locdate = item.get("locdate", "")
        name = item.get("dateName", "").strip()
        if not _LOCDATE.match(locdate) or not name:
            raise publicapi.PublicApiError(f"항목 형식이 예상과 다릅니다: {item}")
        day = f"{locdate[:4]}-{locdate[4:6]}-{locdate[6:]}"
        names = by_date.setdefault(day, [])
        if name not in names:
            names.append(name)

    return [{"date": day, "name": "·".join(names)} for day, names in sorted(by_date.items())]
