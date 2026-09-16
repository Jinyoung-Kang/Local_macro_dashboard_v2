# Local Macro Dashboard v2

글로벌 매크로 지표·연준 순유동성·섹터 로테이션·CFTC COT·KRX 파생·SEC 13F·
국내 수급 레이더를 한 화면에서 보는 대시보드입니다.

[구버전](https://github.com/Jinyoung-Kang/Local-macro-dashboard)(Python 단일
Streamlit 앱)의 **기능을 그대로 유지**하면서, 화면·API·수집을 분리한 다중 언어
스택으로 다시 만들었습니다.

```
┌─────────────┐   REST    ┌──────────────┐   JDBC    ┌────────────┐
│  Next.js    │ ────────▶ │ Spring Boot  │ ────────▶ │ PostgreSQL │
│  React · TS │ ◀──────── │  Java 21     │ ◀──────── │            │
└─────────────┘   JSON    └──────────────┘           └────────────┘
                                │  ▲                        ▲
                          REST  │  │ 캐시                   │ 적재
                                ▼  │                        │
                          ┌──────────────┐          ┌──────────────┐
                          │    Redis     │          │  Collector   │
                          └──────────────┘          │ Python·FastAPI│
                                                    └──────────────┘
                                                            │
                          FRED · KRX · KIS · LS · CFTC · SEC EDGAR · Daum · Naver
```

---

## 1. 기술 스택과 선택 이유

| 영역 | 채택 | 왜 |
|---|---|---|
| Frontend | **TypeScript · React 18 · Next.js 15 · Tailwind** | 12개 메뉴가 서로 다른 형태의 표·차트를 쓰므로 컴포넌트 재사용이 크게 이득입니다. |
| Backend | **Java 21 · Spring Boot 3.4** | 화면 코드에 흩어져 있던 계산(수익률 매트릭스, 13F 분기 대비 액션, COT 요약, 교차 검증 판정)을 한 계층에 모아 타입으로 고정했습니다. |
| Store | **PostgreSQL 16** | 수집기와 API가 서로 다른 프로세스·컨테이너라 SQLite 파일 공유가 불가능합니다. JSONB로 저장해 백엔드가 SQL로 직접 질의합니다. |
| Cache | **Redis 7** | 같은 저장본을 여러 화면이 동시에 읽습니다. 응답 캐시로 DB 왕복을 줄입니다. |
| Data | **Python 3.11 · FastAPI · pandas · yfinance · pykrx** | 수집·파싱 로직은 구버전에서 검증된 자산입니다. 같은 라이브러리를 그대로 써서 **데이터 동등성**을 지켰습니다. |
| DevOps | **Docker Compose · GitHub Actions** | 다섯 개 프로세스를 한 명령으로 띄우고, 세 언어의 테스트를 매 푸시마다 돌립니다. |

### 채택하지 않은 스택과 이유

요청 목록 중 아래는 **이 시스템에 득이 없다고 판단해 제외**했습니다.
(필요해지면 붙일 수 있도록 경계는 열어 두었습니다.)

| 제외 | 이유 | 필요해지면 |
|---|---|---|
| Kafka · RabbitMQ · Debezium(CDC) | 수집 주기가 5분/1시간/12시간이고 태스크가 11개입니다. 초당 수천 건이 아니라 **하루 수천 건** 규모라 브로커가 처리할 트래픽 자체가 없습니다. 운영 부담만 늘어납니다. | 수집기를 여러 대로 늘리거나 실시간 체결 스트림(KIS WebSocket)을 붙일 때 |
| BigQuery · ClickHouse · S3 | 전체 데이터가 수십 MB 수준이고 질의는 "최근 N일"이 대부분입니다. PostgreSQL 인덱스로 충분합니다. | 틱 데이터나 다년치 종목 전수 분석을 시작할 때 |
| Flutter · Dart 앱 | 웹 화면과 **같은 기능을 두 벌** 유지해야 합니다. 이 대시보드는 표와 차트 중심이라 모바일 웹으로 충분합니다. | 푸시 알림·백그라운드 감시가 필요해질 때 |
| AWS EKS · Terraform · ArgoCD · Grafana(LGTM) | 1인용 로컬 대시보드입니다. 쿠버네티스 클러스터 운영 비용이 앱 자체보다 큽니다. | 여러 사용자에게 서비스하게 될 때 |

> 구버전 README의 실행 환경은 "macOS 로컬"이었습니다. v2도 **로컬 실행이 기본**이며,
> 컨테이너로 묶여 있어 어느 OS에서든 같은 명령으로 뜹니다.

---

## 2. 실행

### 2-1. Docker Compose (권장)

```bash
git clone https://github.com/Jinyoung-Kang/Local_macro_dashboard_v2.git
cd Local_macro_dashboard_v2

cp .env.example .env      # 비밀번호와 API 키를 채웁니다 (키가 없어도 실행됩니다)
docker compose up -d --build
```

- 화면: <http://localhost:3000> (기본 비밀번호 `admin1234@` — `.env`의 `APP_PASSWORD`)
- API: <http://localhost:8080/api/health>
- 수집기: <http://localhost:8000/status>

### 2-2. 개발 모드 (각 서비스 따로)

```bash
# 0) 인프라
docker compose up -d postgres redis

# 1) 수집기
cd collector
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
DATABASE_URL=postgresql://macro:macro@localhost:5432/macrodash \
  uvicorn app.main:app --reload --port 8000

# 2) 백엔드
cd backend
DATABASE_URL=jdbc:postgresql://localhost:5432/macrodash \
  APP_PASSWORD=admin1234@ mvn spring-boot:run

# 3) 화면
cd frontend
npm install
NEXT_PUBLIC_API_BASE=http://localhost:8080 npm run dev
```

### 2-3. 키가 없을 때의 동작 (구버전과 동일)

| 키 | 없으면 |
|---|---|
| `FRED_API_KEY` | FRED 웹 CSV로 폴백 (대부분 정상 동작) |
| `KRX_API_KEY` | KRX 선물이 KODEX 200 기반 **추정치**로 폴백 (`isEstimated=true` 표시) |
| `KIS_APP_KEY` / `KIS_APP_SECRET` | 장중 수급 가집계와 **교차 검증** 비활성화 |
| `LS_APP_KEY` / `LS_APP_SECRET` | 수급 레이더 폴백 체인에서 LS 단계만 건너뜀 |
| `TOSS_*` | 토스 연결 테스트 메뉴만 비활성화 |
| AI 키 (`NVIDIA` / `CEREBRAS` / `CLOUDFLARE`) | AI 리포트·연결 테스트 메뉴만 비활성화 |

---

## 3. 화면 구성 (12개 메뉴 — 구버전과 동일)

| 메뉴 | 경로 | 내용 |
|---|---|---|
| 📊 거시경제 매크로 지표 | `/macro` | 환율·국채·원자재·지수, 10Y−2Y·30Y−2Y 금리차, 신용·변동성 리스크, **심화 지표 5종** |
| 🏢 연준 순유동성 트래커 | `/liquidity` | WALCL − TGA − ON RRP, 구성 항목 분해, 4주/12주 모멘텀 |
| 🔄 섹터 & 자산군 로테이션 | `/sector` | S&P 11개 섹터 + 자산군 수익률·순위·벤치마크 대비 초과성과 |
| 📑 기관 13F 포트폴리오 | `/institutions` | 기관별 분기 보유 종목, 분기 대비 액션, 비중 추이 |
| 🎯 기관 13F Money 교집합 | `/consensus` | 여러 기관 공통 보유·동시 매수/매도 집중도 |
| 🏛️ 글로벌 투기세력 (COT) | `/cot` | CFTC 비상업·상업·비보고 순포지션 6개 자산 |
| 🇰🇷 국내 파생 & 투기세력 | `/krx` | KOSPI200 선물 OI·베이시스·4대 국면·한국판 COT Index·장중 수급 가속도 |
| 📡 외국인/기관 수급 레이더 | `/radar` | 투자자별 순매수 상위, 소스 진단, 누적 이력 |
| 🗄️ 데이터 저장소 상태 | `/status` | 수집 현황·신선도·실패 원인·누락 데이터셋·**교차 검증** |
| 🤖 AI 종합 데이터 분석 | `/ai/report` | 수집 데이터 기반 AI 리포트 (원본 텍스트 확인·복사 가능) |
| 🤖 AI API 연결 테스트 | `/ai/test` | 엔진별 응답·지연·자동 번역 확인 |
| 🔌 토스증권 API 테스트 | `/toss` | 토스 Open API 연결 진단 |

---

## 4. 이 프로젝트가 지키는 규칙

구버전이 여러 차례 사고를 겪으며 정한 규칙들입니다. v2도 **코드와 테스트로**
같은 규칙을 강제합니다.

### 4-1. 수집 실패 시 숫자를 만들어내지 않습니다

- 실패는 `status="fail"`, 빈 결과, `available=false`로 드러납니다.
- 화면은 값이 없으면 `—`로 표시합니다. **0.00%로 채우지 않습니다** —
  "0.00%"는 '데이터 없음'이 아니라 '보합'으로 읽히기 때문입니다.
- 전일 종가를 모르면 "전일 대비 미제공"입니다. 현재가를 전일값처럼 쓰지 않습니다.

### 4-2. 추정치는 추정치라고 말합니다

| 값 | 표시 |
|---|---|
| KRX 수집 실패 시 KODEX 200 기반 선물 가격 | `isEstimated=true` + 화면 경고 배너 |
| MOVE 지수 (Yahoo가 제공하지 않음) | `isProxy=true` + "실제 ICE BofA MOVE 아님" |

추정치는 **누적 이력 테이블에 쓰지 않습니다.** 한 번 섞이면 실제 확정치와
구분할 수 없습니다.

### 4-3. 모르면 "판정 불가"입니다

KOSPI200 선물 4대 국면은 등락률과 미결제약정 증감이 모두 있어야 판정합니다.
구버전은 결측을 0.0으로 메워 `등락률 >= 0`이 항상 참이 되었고, **하락한 날에도
'신규 롱'(강세)** 으로 표시됐습니다(2026-09-11: 실제 −2.13%). 등락률은 이제
연속된 확정 종가에서 직접 계산하고, KRX 보고값은 대조용으로만 남깁니다.

### 4-4. "확인 못 함"과 "일치"를 섞지 않습니다

교차 검증 판정은 네 가지입니다: **일치 / 불일치 / 수집 실패 / 확인 못 함**.
키가 없어 비교하지 못한 것을 "일치"로 표시하면 검증 자체가 거짓말이 됩니다.

시간 조건이 항목마다 반대인 것도 그대로입니다 — KRX는 *일별 확정 종가*, KIS는
*현재가*를 주므로 시세 대조는 **장 마감 후**에만, KIS 수급 가집계 TR은 장중
전용이라 수급 대조는 **정규장 중**에만 가능합니다.

### 4-5. 화면은 수집을 기다리지 않습니다

읽기 모드(`DASHBOARD_READ_MODE`)는 구버전을 그대로 계승합니다.

| 값 | 동작 |
|---|---|
| `auto` (기본) | 저장본이 신선하면 사용, 오래되면 수집기에 수집 요청 |
| `store_only` | 저장본만 사용. 오래돼도 그대로 보여주고 외부를 **절대** 기다리지 않음 |
| `live_only` | 항상 수집 요청 (디버깅용) |

수동 새로고침은 저장본을 **지우지 않습니다.** "이 시각 이전 저장본은 낡은
것으로 본다"는 기준만 세웁니다 — 수집이 실패하면 보여 줄 값이 아예 없어지기
때문입니다.

### 4-6. 과거 조회가 안 되는 소스는 우리가 이력을 쌓습니다

Naver·Daum·KIS는 "가장 최근에 끝난 거래일"만 줍니다. 수집기가 돌 때마다 그날의
수급 랭킹을 `observations`에 적재하고, 외부 소스가 모두 실패하면 그 이력으로
대체합니다. 이때 화면에는 **어느 날짜의 저장본인지**와 "지금 시점의 수급이
아니다"라는 경고가 반드시 함께 뜹니다.

> **수집기를 꾸준히 돌리는 것이 곧 백업입니다.** PostgreSQL 볼륨은 백업할
> 가치가 있습니다.

---

## 5. 수집 작업 (11개 — 구버전과 동일)

| 군 | 작업 | 주기 |
|---|---|---|
| `fast` | `scraper_markets` · `macro_collected` · `radar_rankings` | 5분 |
| `slow` | `fred_series` · `fed_liquidity` · `krx_futures` · `sector_history` · `volatility_history` · `cot_history` · `daum_futures_trend` | 1시간 |
| `weekly` | `sec_13f` | 12시간 |

구버전 CLI는 수집기 REST API로 옮겼습니다.

| 구버전 | v2 |
|---|---|
| `python collector.py --loop` | 상주 스케줄러 (`COLLECTOR_SCHEDULER=true`) |
| `python collector.py --only fast` | `POST /collect?group=fast` |
| `python collector.py --task krx_futures` | `POST /collect/task/krx_futures` |
| `python collector.py --status` | `GET /status` · 화면의 `🗄️ 데이터 저장소 상태` |
| `python collector.py --history` | `GET /task-history` |
| `python collector.py --verify` | `GET /verify/*` + 백엔드 판정 · 화면의 `🔍 교차 검증` |
| `python collector.py --purge-days 400` | `POST /maintenance/purge?days=400` |

---

## 6. 수급 레이더 폴백 체인

```
KIS(장중 가집계) → Daum(API) → Naver → LS(OPEN API) → PyKrx → 누적 이력
```

앞쪽이 성공하면 뒤는 호출되지 않습니다. 화면은 **어느 출처가 실제로 성공했는지**
항상 표시합니다.

> **구버전 버그**: `fetch_ls_deal_ranking()`이 정의만 되어 있고 어디에서도
> 호출되지 않아, LS 키를 정확히 넣어도 화면 데이터가 달라지지 않았습니다.
> v2는 체인에 정상적으로 연결돼 있고 테스트가 순서를 고정합니다.

**LS 포트 주의** — 문서에는 오랫동안 `:8080`이 적혀 있었지만 서버가 그 포트를
더 이상 열어두지 않습니다(31ms 즉시 refused = 방화벽 드롭이 아니라 닫힌 포트).
표준 443을 먼저 쓰고 8080은 보조로만 남깁니다.

---

## 7. 테스트

```bash
# 수집기 (PostgreSQL 필요 — 없으면 저장 계층 테스트만 자동 건너뜀)
cd collector
TEST_DATABASE_URL=postgresql://macro:macro@localhost:5432/macrodash python -m pytest tests -q

# 백엔드 (통합 테스트가 실제 DB를 사용)
cd backend
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/macrodash mvn verify

# 화면 (타입 검사 포함)
cd frontend && npm run lint && npm run build
```

무엇을 고정하고 있는지:

- `collector/tests/test_store.py` — 저장/신선도/누적 idempotency, 죽은 수집기를
  "진행 중"으로 오인하지 않는지, 종목코드 앞자리 0 보존
- `collector/tests/test_market_cards.py` — 정체된 분봉의 일봉 폴백, N/A를 0.00%로
  위장하지 않는지, 엔/원 배율, 일봉 타임스탬프 표기
- `collector/tests/test_krx_futures.py` — 종가 기반 등락률, 하락일이 '신규 롱'으로
  뒤집히지 않는지, 추정치가 미결제약정을 지어내지 않는지
- `collector/tests/test_radar_chain.py` — 폴백 순서, 과거 날짜에서 현재 전용 소스
  건너뛰기, 이력 대체 표시
- `collector/tests/test_tasks.py` — 빈 결과가 기존 저장본을 덮지 않는지,
  추정치가 누적되지 않는지, 13F q1이 q8에서 유도되는지
- `backend/.../VerificationTest.java` — 판정 네 가지 구분, 장 시간 게이트
- `backend/.../SeriesMathTest.java` — 표본 부족이 0.0이 아니라 null인지
- `backend/.../Sec13FServiceTest.java` — 분기 대비 액션 분류, CUSIP 문자열 보존
- `backend/.../DatasetsParityTest.java` — **Python과 Java가 같은 데이터셋 이름을
  쓰는지** (실제 `catalog.py`를 읽어 대조)
- `backend/.../ApiIntegrationTest.java` — 인증 강제, 저장본이 없을 때 500이 아니라
  `available=false`, 수집기가 죽어도 화면이 뜨는지

---

## 8. 구버전과 달라진 점 (의도한 변경)

기능은 유지하되, 구버전이 **스스로 정한 규칙과 충돌하던 부분**만 바로잡았습니다.

| 항목 | 구버전 | v2 | 이유 |
|---|---|---|---|
| FRED 접속 실패 시 순유동성 | `sin()` 파형으로 만든 가짜 시계열 (`is_estimated=True`) | **빈 결과 + "수집 실패"** | 값에 정보가 전혀 없는데 차트는 그럴듯하게 그려집니다. 구버전 README의 "가짜 숫자를 만들지 마세요" 규칙과 충돌 |
| MOVE 네트워크 실패 시 | 사인파 합성 시계열 | 빈 결과 | 같은 이유 |
| KRX 추정치의 미결제약정·베이시스 | `linspace`/`sin`으로 합성 | `null` | 실제 OI와 무관한 숫자를 OI로 표시하면 그 자체가 오류이고 교차 검증도 무의미해집니다 |
| TradingView 수집 | HTML 정규식 파싱 | 공개 **JSON** 엔드포인트 | HTML 구조가 바뀌면 조용히 틀린 값을 주던 경로를 없앴습니다 |
| Naver 수급 수집 | 헤드리스 Chromium 렌더링 | 서버가 주는 HTML 파싱 | iframe 페이지는 완성된 HTML을 주므로 결과가 같고, 브라우저 의존성이 사라집니다 |
| 지표 이름 | `"달러 인덱스 (DXY) :gray[[실시간]]"` | `{key, name, note}` 구조 분리 | 표시용 마크다운을 정규식으로 벗기다 `"달러 인덱스 (DXY) ]"`가 되던 버그의 원인 제거 |
| 국채 보정 연결 키 | 표시 이름 문자열 | 변하지 않는 `key` | 이름을 한 글자만 고쳐도 보정이 조용히 끊기던 구조 제거 |
| 저장 형식 | DataFrame → JSON + dtype 별도 저장 | 명시적 JSON 계약 | 종목코드가 int로 추론돼 앞자리 0이 사라지던 위험 자체를 제거 |
| Placeholder 수급 데이터 | 고정 예시값 폴백 (`is_placeholder`) | 제거 | 어떤 조건에서도 가짜 수급을 보여주지 않습니다 |

---

## 9. 자주 겪는 문제

| 증상 | 원인 / 해결 |
|---|---|
| 화면이 전부 "데이터 없음" | 수집기가 아직 한 번도 돌지 않았습니다. `POST /collect?group=fast` 또는 `🗄️ 데이터 저장소 상태`에서 태스크별 "다시 실행" |
| 로그인 후 401이 반복됨 | `FRONTEND_ORIGIN`과 실제 접속 주소가 달라 쿠키가 막힌 경우입니다(`localhost`와 `127.0.0.1`은 다른 오리진입니다) |
| `수집기에 연결하지 못했습니다` | 백엔드의 `COLLECTOR_URL` 확인. 수집기가 죽어 있어도 저장본으로 화면은 뜹니다 |
| KRX 선물이 "추정치" | `KRX_API_KEY` 미설정. KODEX 200 기반 폴백입니다 |
| 수급 레이더에 "누적 이력" 경고 | 외부 소스가 모두 실패해 저장된 이력을 보여주는 중입니다. 표시된 **날짜**를 확인하세요 |
| 교차 검증이 "확인 못 함"만 나옴 | 시간 조건 때문입니다. 시세 대조는 장 마감 후, 수급 대조는 정규장 중에만 가능합니다 |
| LS API `LS 서버에 접속하지 못했습니다` | **키 문제가 아닙니다.** 망에서 서버에 닿지 못한 상태입니다 |
| LS API `OAuth 토큰 발급 거절` | 이때가 진짜 키 문제입니다. LS 홈에서 **"Open API"** 로 사용등록했는지 확인하세요 |
| AI 메뉴가 비활성화 | AI 키가 하나도 없습니다. NVIDIA/Cerebras/Cloudflare 중 하나만 있어도 동작합니다 |

---

## 10. 문서

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 계층 구조, 데이터 흐름, 저장 스키마
- [docs/MIGRATION.md](docs/MIGRATION.md) — 구버전 파일 → v2 파일 대응표
- [docs/API.md](docs/API.md) — REST 엔드포인트 목록

---

## 11. 데이터 출처와 신뢰도

공식 API와 비공식 웹 소스를 **섞어서** 씁니다. 투자 판단 전에 각 수치의 출처
배지를 반드시 확인하세요.

| 구분 | 출처 | 신뢰도 |
|---|---|---|
| 금리·신용 스프레드·유동성·심화 지표 | FRED 공식 API | 공식 (일별/주간 확정치) |
| 환율·원자재·지수 | yfinance | 15분 지연 |
| 미국채 2Y/10Y/30Y | TradingView 공개 Scanner | **비공식 참고** |
| 국내 수급·파생 | KRX Open API · KIS · LS · pykrx · Daum · Naver | 공식 + 비공식 혼합 |
| 13F 포트폴리오 | SEC EDGAR | 공식 (분기 공시, 45일 지연) |
| CFTC COT | CFTC 공개 API | 공식 (주 1회, 화요일 기준) |
| **MOVE 지수** | `^TNX` 변동성 역산 | ⚠️ **추정치 — 실제 MOVE 아님** |

> 이 대시보드는 **정보 제공용**입니다. 투자 판단과 그 결과의 책임은 이용자에게
> 있습니다.
