"""
app/services/molit.py
국토교통부 아파트 매매 실거래가 — 서울 25개 구.

호출 단위 — 시군구(LAWD_CD 5자리) × 계약연월(DEAL_YMD YYYYMM) 한 번에 그 구·그 달의
전체 거래. 25개 구 × 15개월 = 375회라 한 번에 받지 않고 호출 예산 안에서 나눠 받습니다.

근거 (실제로 동작하는 코드: choiys2/apt-price-dashboard fetch_apt_trades.py,
      david61756/apt-price-monitor monitor.py, PublicDataReader molit.py)
  - 주소: https://apis.data.go.kr/1613000/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade
  - XML. 정상 resultCode는 "00" 또는 "000". body/totalCount로 페이지 처리
  - dealAmount 거래금액(만원, "82,500"처럼 쉼표 포함) · excluUseAr 전용면적(㎡)
  - dealYear/dealMonth/dealDay 계약일 · cdealType 해제여부("O" = 해제된 거래)
  - 주의: 코드가 틀리면 오류 없이 totalCount=0이 옵니다(그 구가 조용히 빠짐).
    그래서 코드는 법정동 코드표에서 말소되지 않은 것만 확인해 적었습니다.

계산하지 않습니다. 해제 거래를 뺀 [거래금액(만원), 전용면적(㎡)] 쌍만 저장하고,
중위값·평당가는 백엔드(analytics/HousingStats)가 계산합니다. 서울 전체 중위값은
구별 중위값의 중위값이 아니라 **모든 거래를 합친** 중위값이어야 하므로 원자료가 필요합니다.
"""
from __future__ import annotations

from .. import publicapi, settings

URL = "https://apis.data.go.kr/1613000/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade"

# 서울 25개 구 (법정동 코드표의 시군구코드, 말소일자 없는 것만 — 2026-09 확인)
SEOUL_GU: dict[str, str] = {
    "11110": "종로구", "11140": "중구", "11170": "용산구", "11200": "성동구", "11215": "광진구",
    "11230": "동대문구", "11260": "중랑구", "11290": "성북구", "11305": "강북구", "11320": "도봉구",
    "11350": "노원구", "11380": "은평구", "11410": "서대문구", "11440": "마포구", "11470": "양천구",
    "11500": "강서구", "11530": "구로구", "11545": "금천구", "11560": "영등포구", "11590": "동작구",
    "11620": "관악구", "11650": "서초구", "11680": "강남구", "11710": "송파구", "11740": "강동구",
}

ROWS_PER_PAGE = 1000
MAX_PAGES = 5


def fetch_month(lawd_cd: str, deal_ymd: str, budget: publicapi.CallBudget) -> dict:
    """
    한 구·한 달의 거래.

    :param lawd_cd: 시군구 코드 5자리
    :param deal_ymd: 계약연월 YYYYMM
    :param budget: 호출 예산 (페이지마다 하나)
    :returns: ``{"trades": [[만원, ㎡], ...], "cancelled": n, "invalid": n, "total": n}``
    :raises publicapi.MissingKey / publicapi.PublicApiError
    """
    key = settings.data_go_kr_key()
    items: list[dict] = []
    total = 0
    for page in range(1, MAX_PAGES + 1):
        if not budget.take():
            raise publicapi.PublicApiError("호출 예산 소진")
        response = publicapi.get(
            URL, {"LAWD_CD": lawd_cd, "DEAL_YMD": deal_ymd, "numOfRows": ROWS_PER_PAGE, "pageNo": page},
            key=key, timeout=30,
        )
        root = publicapi.parse_xml(response, key=key)
        page_items = publicapi.xml_items(root)
        items.extend(page_items)
        total = publicapi.total_count(root) or len(items)
        if not page_items or len(items) >= total:
            break
    else:
        raise publicapi.PublicApiError(f"{MAX_PAGES}페이지를 넘었습니다 ({lawd_cd} {deal_ymd})")
    return summarize(items, total)


def _number(text: str | None) -> float | None:
    cleaned = (text or "").replace(",", "").strip()
    try:
        return float(cleaned) if cleaned else None
    except ValueError:
        return None


def summarize(items: list[dict], total: int) -> dict:
    """
    해제 거래를 빼고 [거래금액(만원), 전용면적(㎡)]만 남깁니다.

    주의사항
      - 해제(cdealType="O")된 거래는 실제로 성사되지 않은 계약입니다. 넣으면
        같은 집이 두 번 세어지거나 없는 가격이 중위값을 끌어당깁니다.
      - 금액·면적이 없거나 0 이하인 행은 버리고 개수만 셉니다(0으로 나누기 방지).
    """
    trades, cancelled, invalid = [], 0, 0
    for item in items:
        if (item.get("cdealType") or "").strip().upper() == "O":
            cancelled += 1
            continue
        amount = _number(item.get("dealAmount"))
        area = _number(item.get("excluUseAr"))
        if not amount or not area or amount <= 0 or area <= 0:
            invalid += 1
            continue
        trades.append([int(amount), round(area, 2)])
    return {"trades": trades, "cancelled": cancelled, "invalid": invalid, "total": total}
