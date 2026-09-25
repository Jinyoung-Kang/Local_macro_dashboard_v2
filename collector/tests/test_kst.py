"""
tests/test_kst.py
화면에 나가는 시각 문자열 형식 — "YYYY-MM-DD HH:MM KST" (초 없음, 날짜 항상 포함).
"""
from __future__ import annotations

from datetime import datetime, timezone

from app import kst


def test_UTC_시각을_KST_분_단위로():
    assert kst.stamp(datetime(2026, 9, 25, 14, 43, 9, tzinfo=timezone.utc)) == "2026-09-25 23:43 KST"


def test_자정을_넘기면_날짜도_바뀐다():
    # 예전 카드는 시각만 있어서 이런 경우 어느 날 값인지 알 수 없었습니다.
    assert kst.stamp(datetime(2026, 9, 25, 15, 30, tzinfo=timezone.utc)) == "2026-09-26 00:30 KST"


def test_시간대가_없으면_KST로_간주():
    assert kst.stamp(datetime(2026, 9, 25, 9, 5, 59)) == "2026-09-25 09:05 KST"
