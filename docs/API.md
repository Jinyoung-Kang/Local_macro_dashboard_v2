# REST API

두 개의 서비스가 API를 노출합니다.

- **백엔드** `http://localhost:8080` — 화면이 쓰는 API. 인증 필요.
- **수집기** `http://localhost:8000` — 내부용. 백엔드만 호출합니다.

## 1. 백엔드 (`/api`)

### 인증

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/login` | `{"password": "..."}` → httpOnly 세션 쿠키 |
| GET | `/api/auth/session` | `{"authenticated": bool}` |
| POST | `/api/auth/logout` | 쿠키 만료 |

로그인·세션·헬스체크를 제외한 모든 `/api/**`는 유효한 세션 쿠키를 요구합니다
(없으면 401).

### 📊 매크로

| 경로 | 설명 |
|---|---|
| `GET /api/macro/overview` | 카드 + 스프레드 + 신선도 |
| `GET /api/macro/risk` | VIX·MOVE·HY OAS·CP 스프레드·STLFSI4 |
| `GET /api/macro/advanced` | 심화 지표 5종 (해석·백분위 포함) |
| `GET /api/macro/scraped` | 비공식 참고 시세 |
| `GET /api/macro/spread?longId=DGS10&shortId=DGS2` | 공식 일별 스프레드 시계열 |
| `GET /api/macro/fred/{seriesId}?years=10` | FRED 시리즈 원본 |
| `GET /api/macro/ticker?symbol=^VIX&period=1y` | 개별 지표 차트 |

### 나머지 메뉴

| 경로 | 설명 |
|---|---|
| `GET /api/liquidity?years=3` | 연준 순유동성 |
| `GET /api/sector/rotation?period=1M` | 섹터·자산군 수익률·순위 |
| `GET /api/sector/momentum` | 1주/1개월/3개월 모멘텀 순위 |
| `GET /api/sec13f/institutions` | 기관 목록 |
| `GET /api/sec13f/portfolio?cik=&quarters=8&topN=30` | 기관 포트폴리오 + 분기 대비 액션 |
| `GET /api/sec13f/consensus?ciks=&reportDate=&minHolders=2` | 교집합 |
| `GET /api/cot/assets`, `/api/cot/overview`, `/api/cot/asset?name=` | CFTC COT |
| `GET /api/krx/futures?days=60` | KOSPI200 선물 시계열 + 최신 판정 |
| `GET /api/krx/investor-trend` | Daum 투자주체별 수급 (계약수) |
| `GET /api/krx/intraday?minutes=30` | 장중 수급 가속도 |
| `GET /api/radar/options` | 선택지 목록 |
| `GET /api/radar/ranking?market=&investor=&tradeType=&topN=&intervalType=` | 수급 랭킹 |
| `GET /api/radar/history?market=&investor=&tradeType=` | 누적 이력 |
| `GET /api/radar/diagnostics` | 5개 소스 연결 진단 |

### 🗄️ 상태 · 검증

| 경로 | 설명 |
|---|---|
| `GET /api/status` | 수집 현황·신선도·누락 데이터셋 |
| `GET /api/status/tasks` | 수집 작업 목록 |
| `GET /api/status/history?task=&limit=40` | 태스크 실행 이력 |
| `POST /api/status/refresh?runFast=true` | 수동 새로고침 |
| `POST /api/status/run/{taskName}` | 특정 태스크 실행 |
| `POST /api/verification` | 교차 검증 (판정 포함) |

### 🤖 AI · 🔌 토스

| 경로 | 설명 |
|---|---|
| `GET /api/ai/engines` | 엔진 목록 + 키 보유 여부 |
| `GET /api/ai/report-types` | 리포트 종류 |
| `GET /api/ai/snapshot-text` | AI에 전달되는 원본 텍스트 |
| `POST /api/ai/report` | `{engineId, reportType, extraInstruction}` |
| `POST /api/ai/test?engineId=&prompt=` | 연결 테스트 |
| `GET /api/ai/toss/diagnostics` | 토스 연결 진단 |
| `GET /api/ai/toss/exchange-rate?base=USD&quote=KRW` | 환율 조회 |
| `GET /api/ai/toss/indices?symbols=KOSPI,KOSDAQ` | 지수 조회 |

## 2. 수집기 (내부용)

`COLLECTOR_API_TOKEN`을 설정하면 쓰기 계열 요청에 `X-Service-Token` 헤더가
필요합니다.

| 경로 | 설명 |
|---|---|
| `GET /health` | 헬스체크 |
| `GET /status` | 저장 현황 + 키 보유 + 수집 주기 |
| `GET /tasks` | 태스크 목록 |
| `GET /task-history?task=&limit=` | 실행 이력 |
| `POST /collect?group=fast\|slow\|weekly\|all&wait=true` | 작업군 실행 |
| `POST /collect/task/{taskName}` | 태스크 1건 실행 |
| `POST /refresh?scope=global` | 새로고침 기준 시각 갱신 |
| `POST /maintenance/purge?days=400` | 오래된 누적 이력 정리 |
| `GET /live/radar?...` | 수급 랭킹 즉시 수집 (폴백 체인 전체) |
| `GET /live/ticker/{symbol}?period=` | 티커 시계열 즉시 수집 |
| `GET /live/daum-intraday?minutes=30` | 장중 수급 가속도 |
| `GET /live/radar-history`, `/live/radar-history-dates` | 누적 이력 |
| `GET /verify/readings?market=&investor=&tradeType=` | 검증용 원자료 (판정은 백엔드) |
| `GET /diagnostics/connections` | KIS·LS·Daum·Naver·PyKrx 진단 |
| `GET /diagnostics/toss`, `/toss/*` | 토스 진단·조회 |
| `GET /catalog` | 지표·기관·ETF·COT 정의 |

FastAPI 자동 문서: <http://localhost:8000/docs>

## 3. 공통 응답 규칙

- 값이 없으면 `null`입니다. **0으로 채우지 않습니다.**
- 저장본이 없으면 HTTP 200 + `{"available": false, "message": "..."}` 입니다.
  화면이 500 에러 페이지 대신 안내를 띄울 수 있어야 하기 때문입니다.
- 추정치는 `isEstimated` / `isProxy`로 표시되며, 화면은 이를 반드시 경고로
  노출해야 합니다.
- 수급 랭킹이 누적 이력으로 대체된 경우 `isHistorical=true`와 `historyDate`,
  `warning`이 함께 옵니다.
