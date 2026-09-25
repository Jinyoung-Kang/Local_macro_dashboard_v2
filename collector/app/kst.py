"""
app/kst.py
화면에 나가는 시각 문자열의 단일 형식.

형식 — ``"YYYY-MM-DD HH:MM KST"`` (분 단위, 날짜 포함).
  - 초는 쓰지 않습니다. 수집 주기가 분 단위라 초는 정보가 아니라 잡음입니다.
  - 날짜는 항상 씁니다. 예전 카드는 "23:33:00 KST"처럼 시각만 있어서 밤을 넘기거나
    휴장일이 끼면 어느 날 값인지 알 수 없었습니다.

형식을 바꿀 일이 생기면 여기 한 곳만 고치세요. 백엔드(Snapshot.KST_FORMAT)와
화면(lib/format.ts formatKst)도 같은 형식을 씁니다.
"""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")
FORMAT = "%Y-%m-%d %H:%M KST"


def stamp(moment: datetime | None = None) -> str:
    """
    KST 시각 문자열.

    :param moment: 시각 (시간대가 없으면 KST로 간주). 없으면 지금
    :returns: 예 ``"2026-09-25 23:43 KST"``
    """
    if moment is None:
        moment = datetime.now(KST)
    elif moment.tzinfo is None:
        moment = moment.replace(tzinfo=KST)
    return moment.astimezone(KST).strftime(FORMAT)
