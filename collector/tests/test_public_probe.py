"""
tests/test_public_probe.py
공공 API 연결 진단.

고정하는 규칙
  1. 키가 없으면 호출하지 않고 "키 미설정".
  2. 실패 사유에 키가 남지 않는다(진단 결과는 화면에 그대로 뜹니다).
  3. 결과에는 키 값이 없고 설정 여부(bool)만 있다.
"""
from __future__ import annotations

import json
from types import SimpleNamespace

from app.services import publicprobe

KEY = "Abc+def/ghi==XYZ0123456789"


def test_키가_없으면_호출하지_않는다(monkeypatch):
    monkeypatch.setattr(publicprobe.settings, "data_go_kr_key", lambda: "")
    monkeypatch.setattr(publicprobe.settings, "dart_key", lambda: "")
    monkeypatch.setattr(publicprobe.publicapi, "get", lambda *a, **k: (_ for _ in ()).throw(AssertionError("호출 금지")))

    result = publicprobe.run()

    assert all(api["detail"] == "키 미설정" and not api["ok"] for api in result["apis"])
    assert result["keys"] == {"DATA_GO_KR_SERVICE_KEY": False, "DART_API_KEY": False}


def test_결과_어디에도_키가_없다(monkeypatch):
    monkeypatch.setattr(publicprobe.settings, "data_go_kr_key", lambda: KEY)
    monkeypatch.setattr(publicprobe.settings, "dart_key", lambda: KEY)

    def fake_get(url, params, **kwargs):
        # 게이트웨이가 요청 URL을 그대로 되돌려 주는 최악의 경우를 흉내 냅니다.
        return SimpleNamespace(text=f"<html>blocked {url}?serviceKey={KEY}</html>", status_code=500,
                               json=lambda: {"status": "900", "message": f"bad key {KEY}"})

    monkeypatch.setattr(publicprobe.publicapi, "get", fake_get)
    result = publicprobe.run()
    text = json.dumps(result, ensure_ascii=False)

    assert not any(api["ok"] for api in result["apis"])
    assert "Abc" not in text and "XYZ0123456789" not in text


def test_성공하면_요약을_보여_준다(monkeypatch):
    monkeypatch.setattr(publicprobe.settings, "data_go_kr_key", lambda: KEY)
    monkeypatch.setattr(publicprobe.settings, "dart_key", lambda: "")
    monkeypatch.setattr(publicprobe.kasi, "fetch_year", lambda year: [{"date": "x", "name": "y"}] * 3)

    ok = publicprobe._run("특일", True, publicprobe._kasi)

    assert ok["ok"] and "공휴일 3건" in ok["detail"] and ok["elapsedMs"] is not None
