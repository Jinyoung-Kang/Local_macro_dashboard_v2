"""
tests/test_molit.py
국토부 아파트 매매 실거래가 (서울 25개 구).

응답 형태의 근거 — 실제 응답 표본은 없습니다(이 환경에서 apis.data.go.kr 접속 불가).
필드는 실제로 동작하는 코드(choiys2/apt-price-dashboard, david61756/apt-price-monitor)가
읽는 이름만 씁니다: dealAmount(만원, 쉼표) · excluUseAr(㎡) · cdealType("O"=해제).
정상 resultCode는 "000"(국토부) — 공공데이터포털 공통 봉투.

고정하는 규칙
  1. 해제 거래와 금액·면적이 없는 행은 빼고 개수만 남긴다.
  2. 서울 25개 구 코드가 모두 있다(코드가 틀리면 API가 오류 없이 0건을 줍니다).
  3. 최근 두 달은 매번 다시 받고, 그 이전은 이미 있으면 부르지 않는다.
  4. 호출 예산을 넘기지 않는다.
"""
from __future__ import annotations

from types import SimpleNamespace


from app import publicapi
from app.services import molit


def test_해제_거래와_잘못된_행을_뺀다():
    items = [
        {"dealAmount": "182,500", "excluUseAr": "84.97", "cdealType": ""},
        {"dealAmount": "90,000", "excluUseAr": "59.9", "cdealType": "O"},
        {"dealAmount": "", "excluUseAr": "59.9"},
        {"dealAmount": "50,000", "excluUseAr": "0"},
    ]
    assert molit.summarize(items, 4) == {
        "trades": [[182500, 84.97]], "cancelled": 1, "invalid": 2, "total": 4,
    }


def test_서울_25개_구():
    assert len(molit.SEOUL_GU) == 25
    assert all(code.startswith("11") and len(code) == 5 for code in molit.SEOUL_GU)
    assert molit.SEOUL_GU["11680"] == "강남구"


def test_페이지를_totalCount까지_받는다(monkeypatch):
    monkeypatch.setattr(molit.settings, "data_go_kr_key", lambda: "k" * 20)
    pages = []

    def fake_get(url, params, **kwargs):
        pages.append(params["pageNo"])
        item = "<item><dealAmount>100,000</dealAmount><excluUseAr>84.9</excluUseAr><cdealType> </cdealType></item>"
        text = (f"<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header>"
                f"<body><items>{item}</items><totalCount>2</totalCount></body></response>")
        return SimpleNamespace(text=text, status_code=200)

    monkeypatch.setattr(molit.publicapi, "get", fake_get)
    result = molit.fetch_month("11680", "202608", publicapi.CallBudget(10))
    assert pages == [1, 2] and len(result["trades"]) == 2


class _FakeStore:
    def __init__(self, have):
        self.have = set(have)
        self.saved = []

    def observation_keys(self, dataset, start):
        return self.have

    def put_observations(self, dataset, obs_date, rows, entity_key):
        self.saved.append((obs_date, rows[0]["lawd"]))
        return 1


def test_최근_두_달은_다시_받고_이전_달은_있으면_건너뛴다(monkeypatch):
    from datetime import datetime

    from app import tasks

    months = tasks._recent_months(datetime.now(tasks.KST).date(), tasks.APT_MONTHS)
    have = {(m, lawd) for m in months for lawd in molit.SEOUL_GU}  # 전부 이미 있음
    fake = _FakeStore(have)
    monkeypatch.setattr(tasks, "store", fake)
    monkeypatch.setattr(tasks.molit_service, "fetch_month",
                        lambda lawd, ymd, budget: (budget.take(), {"trades": [], "cancelled": 0, "invalid": 0, "total": 0})[1])

    result = tasks.task_seoul_apartments()

    assert {m for m, _ in fake.saved} == set(months[:tasks.APT_REFRESH_MONTHS])
    assert len(fake.saved) == tasks.APT_REFRESH_MONTHS * 25
    assert "백필 남음" not in result


def test_호출_예산을_넘기지_않고_남은_양을_알린다(monkeypatch):
    from app import tasks

    fake = _FakeStore(set())
    monkeypatch.setattr(tasks, "store", fake)
    monkeypatch.setattr(tasks, "APT_CALL_BUDGET", 30)
    monkeypatch.setattr(tasks.molit_service, "fetch_month",
                        lambda lawd, ymd, budget: (budget.take(), {"trades": [], "cancelled": 0, "invalid": 0, "total": 0})[1])

    result = tasks.task_seoul_apartments()

    assert len(fake.saved) == 30
    assert f"백필 남음 약 {25 * tasks.APT_MONTHS - 30}건" in result


def test_최근_월_목록():
    from datetime import date

    from app import tasks
    assert tasks._recent_months(date(2026, 2, 15), 3) == ["2026-02-01", "2026-01-01", "2025-12-01"]
