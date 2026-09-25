"""
app/services/dart.py
금융감독원 Open DART — 국내 상장사 재무제표 주요계정.

무엇을 받는가
  1. 고유번호(corpCode.xml) — 종목코드(6자리)를 DART 고유번호(8자리)로 바꾸는 표.
     ZIP 안에 CORPCODE.xml이 들어 있습니다. 전체 공시대상회사(비상장 포함)라
     상장사(stock_code가 있는 행)만 남깁니다.
  2. 다중회사 주요계정(fnlttMultiAcnt.json) — 사업보고서의 재무상태표·손익계산서
     주요 계정. 한 호출에 여러 회사를 쉼표로 묶어 보냅니다.

계산하지 않습니다. 부채비율·증가율은 백엔드(analytics/KrFundamentals)가 합니다.
수집기는 DART가 준 계정과 금액을 **그대로** 옮기기만 합니다.

근거 (공식 개발가이드를 옮긴 kenshin579/opendart-go docs/api)
  - 응답: status("000" 정상, "013" 조회된 데이터 없음) · message · list
  - list 항목: rcept_no, bsns_year, stock_code, reprt_code, account_nm,
    fs_div(OFS/CFS), sj_div(BS/IS), thstrm_amount, frmtrm_amount,
    bfefrmtrm_amount(사업보고서만), currency. 금액은 "9,999,999,999" 형식 문자열
  - bsns_year는 2015년 이후만 제공
"""
from __future__ import annotations

import io
import json
import re
import xml.etree.ElementTree as ET
import zipfile

from .. import publicapi, settings

BASE = "https://opendart.fss.or.kr/api"
REPORT_ANNUAL = "11011"  # 사업보고서

# 한 호출에 묶을 회사 수. 공식 문서에 상한이 적혀 있지 않아 보수적으로 둡니다.
# 너무 크면 한 회사의 오류가 묶음 전체를 실패시키는 범위도 커집니다.
BATCH = 20

# ZIP 폭탄 방지. 실제 CORPCODE.xml은 수십 MB 수준입니다.
_MAX_ZIP_BYTES = 50 * 1024 * 1024
_MAX_XML_BYTES = 200 * 1024 * 1024

_STOCK_CODE = re.compile(r"^\d{6}$")
_CORP_CODE = re.compile(r"^\d{8}$")


def _key() -> str:
    return settings.dart_key()


def _json(response, key: str) -> dict:
    """DART JSON 봉투를 읽습니다. 정상이면 본문, 013이면 빈 list가 담긴 본문."""
    try:
        body = response.json()
    except (ValueError, json.JSONDecodeError):
        head = publicapi.scrub(" ".join((response.text or "").split())[:80], key)
        raise publicapi.PublicApiError(f"HTTP {response.status_code} — JSON이 아닌 응답: {head}") from None

    status = str(body.get("status", ""))
    if status in ("000", ""):
        return body
    if status == "013":  # 조회된 데이터가 없습니다
        return {"status": status, "list": []}
    message = str(body.get("message", "")).strip()
    raise publicapi.PublicApiError(publicapi.scrub(f"DART status={status} {message}", key))


# ==============================================================================
# 1. 고유번호
# ==============================================================================
def fetch_corp_codes() -> dict[str, dict]:
    """
    상장사 종목코드 → 고유번호·회사명.

    :returns: ``{"005930": {"corpCode": "00126380", "name": "삼성전자"}, ...}``
    :raises publicapi.MissingKey: DART_API_KEY 미설정
    :raises publicapi.PublicApiError: 인증 오류(이때는 ZIP 대신 오류 본문이 옴)·형식 이상
    """
    key = _key()
    response = publicapi.get(f"{BASE}/corpCode.xml", {}, key=key, key_param="crtfc_key", timeout=60)
    content = response.content or b""
    if len(content) > _MAX_ZIP_BYTES:
        raise publicapi.PublicApiError(f"고유번호 파일이 비정상적으로 큽니다 ({len(content):,} bytes)")
    if not content.startswith(b"PK"):
        # ZIP이 아니면 오류 봉투(XML 또는 JSON)입니다.
        text = content[:400].decode("utf-8", "replace")
        status = re.search(r"<status>(\d+)</status>|\"status\"\s*:\s*\"(\d+)\"", text)
        message = re.search(r"<message>(.*?)</message>|\"message\"\s*:\s*\"(.*?)\"", text)
        code = next((g for g in (status.groups() if status else ()) if g), "?")
        msg = next((g for g in (message.groups() if message else ()) if g), "")
        raise publicapi.PublicApiError(publicapi.scrub(f"DART status={code} {msg}".strip(), key))
    return parse_corp_codes(content)


def parse_corp_codes(zip_bytes: bytes) -> dict[str, dict]:
    """
    corpCode.xml ZIP을 상장사 표로 옮깁니다.

    주의사항 — 압축을 풀기 전에 선언된 크기를 확인합니다. 외부에서 받은 ZIP을
    크기 확인 없이 메모리에 풀면 작은 파일 하나로 메모리를 다 쓸 수 있습니다.
    """
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
        names = [n for n in archive.namelist() if n.upper().endswith(".XML")]
        if not names:
            raise publicapi.PublicApiError("고유번호 ZIP 안에 XML이 없습니다")
        info = archive.getinfo(names[0])
        if info.file_size > _MAX_XML_BYTES:
            raise publicapi.PublicApiError(f"고유번호 XML이 비정상적으로 큽니다 ({info.file_size:,} bytes)")
        root = ET.fromstring(archive.read(names[0]))

    out: dict[str, dict] = {}
    for row in root.findall("list"):
        stock = (row.findtext("stock_code") or "").strip()
        corp = (row.findtext("corp_code") or "").strip()
        if _STOCK_CODE.match(stock) and _CORP_CODE.match(corp):
            out[stock] = {"corpCode": corp, "name": (row.findtext("corp_name") or "").strip()}
    return out


# ==============================================================================
# 2. 주요계정
# ==============================================================================
def fetch_accounts(corp_codes: list[str], bsns_year: int) -> list[dict]:
    """
    여러 회사의 사업보고서 주요계정 원본 행.

    :param corp_codes: DART 고유번호 목록 (최대 :data:`BATCH`개)
    :param bsns_year: 사업연도
    :returns: DART list 원본 행. 해당 연도 보고서가 없는 회사는 행이 없습니다
    """
    key = _key()
    response = publicapi.get(
        f"{BASE}/fnlttMultiAcnt.json",
        {"corp_code": ",".join(corp_codes), "bsns_year": str(bsns_year), "reprt_code": REPORT_ANNUAL},
        key=key, key_param="crtfc_key",
    )
    return list(_json(response, key).get("list") or [])


def normalize_account(name: str) -> str:
    """
    계정명을 비교용으로 맞춥니다: 공백 제거, 끝의 괄호 설명 제거.

    "당기순이익(손실)" · "영업이익 (손실)" 같은 표기 차이를 흡수합니다.
    무엇으로 바뀌었는지 되짚을 수 있게 원문도 함께 저장합니다(``rawName``).
    """
    compact = re.sub(r"\s+", "", name or "")
    return re.sub(r"\(.*?\)$", "", compact)


def parse_amount(text: str | None) -> float | None:
    """ "1,234,567" · "-1,234" → 숫자. 빈 값·"-"·숫자가 아니면 None (0으로 채우지 않음)."""
    if text is None:
        return None
    cleaned = text.replace(",", "").strip()
    if cleaned in ("", "-"):
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def group_by_company(rows: list[dict]) -> dict[str, dict]:
    """
    원본 행을 회사별 계정표로 묶습니다.

    연결(CFS)과 별도(OFS)가 함께 오면 **연결을 씁니다.** 지주회사·그룹사는 별도
    기준으로 보면 자회사 실적이 빠져 실제와 크게 다릅니다. 연결이 없는 회사만
    별도를 씁니다. 어느 쪽을 썼는지는 ``fsDiv``에 남깁니다.

    :returns: ``{stock_code: {bsnsYear, fsDiv, rceptNo, currency, accounts: {정규화 계정명:
              {rawName, sj, current, previous, beforePrevious}}}}``
    """
    by_company: dict[str, dict[str, list[dict]]] = {}
    for row in rows:
        stock = str(row.get("stock_code") or "").strip()
        if not _STOCK_CODE.match(stock):
            continue
        by_company.setdefault(stock, {}).setdefault(str(row.get("fs_div") or ""), []).append(row)

    out: dict[str, dict] = {}
    for stock, divisions in by_company.items():
        fs_div = "CFS" if divisions.get("CFS") else "OFS"
        chosen = divisions.get(fs_div) or []
        if not chosen:
            continue
        accounts: dict[str, dict] = {}
        for row in chosen:
            name = normalize_account(str(row.get("account_nm") or ""))
            if not name or name in accounts:
                continue
            accounts[name] = {
                "rawName": row.get("account_nm"),
                "sj": row.get("sj_div"),
                "current": parse_amount(row.get("thstrm_amount")),
                "previous": parse_amount(row.get("frmtrm_amount")),
                "beforePrevious": parse_amount(row.get("bfefrmtrm_amount")),
            }
        first = chosen[0]
        out[stock] = {
            "bsnsYear": str(first.get("bsns_year") or ""),
            "fsDiv": fs_div,
            "rceptNo": first.get("rcept_no"),
            "currency": first.get("currency"),
            "accounts": accounts,
        }
    return out
