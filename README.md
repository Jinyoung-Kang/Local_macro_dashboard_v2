# Local Macro Dashboard v2

글로벌 매크로 지표·연준 순유동성·섹터 로테이션·CFTC COT·KRX 파생·SEC 13F·
국내 수급 레이더를 한 화면에서 보는 대시보드입니다.

[구버전](https://github.com/Jinyoung-Kang/Local-macro-dashboard)(Python 단일
Streamlit 앱)의 **기능을 그대로 유지**하면서, 화면·API·수집을 분리한 다중 언어
스택으로 다시 만들었습니다.

**실행 환경** — macOS(Apple Silicon 포함) 로컬 · Docker Desktop 한 줄 실행 또는
Homebrew 네이티브 실행 · 권장 경로 `~/Projects/Local-macro-dashboard-v2`

```
┌─────────────┐   REST    ┌──────────────┐   JDBC    ┌────────────┐
│  Next.js    │ ────────▶ │ Spring Boot  │ ────────▶ │ PostgreSQL │
│  React · TS │ ◀──────── │  Java 21     │ ◀──────── │            │
└─────────────┘   JSON    └──────────────┘           └────────────┘
                                │  ▲                        ▲
                          REST  │  │ 캐시                    │ 적재
                                ▼  │                        │
                          ┌──────────────┐          ┌──────────────┐
                          │    Redis     │          │  Collector    │
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

### 2-1. 저장 · 실행 (Docker, 권장)

```bash
# 저장 — 저장소 이름(밑줄)과 폴더 이름(하이픈)이 다르므로 경로를 직접 지정합니다
git clone https://github.com/Jinyoung-Kang/Local_macro_dashboard_v2.git \
  ~/Projects/Local-macro-dashboard-v2
cd ~/Projects/Local-macro-dashboard-v2

make setup     # .env 생성 · 세션 키 자동 생성 · 키/포트 확인
make up        # 전체 스택 기동 (최초 빌드 5~10분)
make collect   # 첫 데이터 수집 — 이걸 해야 화면에 숫자가 찹니다
open http://localhost:3000
```

| 주소 | 용도 |
|---|---|
| <http://localhost:3000> | 화면 (비밀번호는 `.env`의 `APP_PASSWORD`) |
| <http://localhost:8080/api/health> | 백엔드 상태 |
| <http://localhost:8000/docs> | 수집기 API 문서 |

`make setup`은 `.env`를 만들고 **세션 서명 키를 무작위로 생성**합니다. 그다음
`.env`에서 `APP_PASSWORD`부터 바꾸세요 — 이 값 하나가 대시보드 전체의 접근
통제입니다.

**자주 쓰는 명령** (`make help`로 전체 목록)

```bash
make update && make up   # 최신 코드 받기 → 다시 빌드·기동  (git pull 대신)
open http://localhost:3000

make version           # 지금 돌고 있는 코드가 어느 브랜치·커밋인지
make status            # 수집 현황 (구버전 collector.py --status)
make doctor            # 데이터가 안 보일 때 — 어디가 막혔는지 한 번에 진단
make logs S=collector  # 특정 서비스 로그
make down / make up    # 정지 / 재기동
make backup            # DB 백업 → backups/
make test              # 세 언어 테스트 전부
```

**Docker로 실행하면 맥에 Java·Maven·Node·psql을 설치할 필요가 없습니다.**
전부 컨테이너 안에 있습니다. DB 셸이 필요하면 `make db`를 쓰세요.

컨테이너에는 `restart: unless-stopped`가 걸려 있어 **맥을 재부팅해도 Docker
Desktop이 뜨면 자동으로 복구**됩니다.

> 📘 맥 기준 상세 절차(Docker 설치, 포트 변경, launchd 상주, 문제 해결)는
> **[docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md)** 에 있습니다.

### 2-1-1. 브랜치와 업데이트 — `git pull` 대신 `make update`

**기본 브랜치는 `main`이고, 실행도 `main`에서 합니다.** 새 작업은 작업용
브랜치(`claude/…`)에 먼저 올라가고, 확인이 끝나면 `main`에 합칩니다. 합친
브랜치는 지웁니다.

```bash
make update    # 모든 브랜치 정보를 받고 → 지금 브랜치를 당기고
               # → "다른 브랜치에 더 새로운 작업이 있는지"까지 알려 줍니다
make up        # 받은 코드로 다시 빌드·기동
```

> **`git pull`을 쓰지 마세요.** `git pull`은 *지금 체크아웃된 브랜치*만
> 당깁니다. 새 작업이 다른 브랜치에 있으면 **아무것도 받지 않고 조용히
> 끝나고**, 이어서 `make up`을 해도 예전 코드가 그대로 다시 뜹니다. 화면은
> 멀쩡한데 고친 것이 하나도 없는 상태가 됩니다. `make update`는 그 경우를
> 이렇게 알려 줍니다.
>
> ```
> ❗ 다른 브랜치에 이 브랜치가 갖고 있지 않은 작업이 있습니다.
>       origin/claude/relaxed-wright-97hxa8 — 이 브랜치에 없는 커밋 2개 (마지막 작업 2026-09-17 12:03)
>
>     최신 작업으로 옮기려면:  git checkout <위 브랜치 이름>  &&  make up
> ```

**지금 화면이 어느 코드인지**는 두 곳에서 확인합니다 — 화면 **왼쪽 아래**
(`© 2026 Local Macro Dashboard v2 · main@1e4d3be`)와 터미널의 `make version`.
`make up`도 마지막에 빌드한 브랜치·커밋을 찍고, 받을 것이 남아 있으면
경고합니다. 고친 기능이 화면에 안 보이면 **이 값부터** 보세요.

작업 브랜치를 `main`에 합치고 정리하는 순서(저장소 주인이 하는 일):

```bash
git checkout main
git merge --ff-only origin/claude/<브랜치>   # 확인이 끝난 작업만
git push origin main
git push origin --delete claude/<브랜치>     # 합친 브랜치는 지웁니다
git remote prune origin
```

### 2-2. 개발 모드 (Docker 없이 / 코드 고치며 실행)

```bash
make infra            # PostgreSQL·Redis만 컨테이너로

make dev-collector    # 터미널 1 — 자동 리로드
make dev-backend      # 터미널 2
make dev-frontend     # 터미널 3 — 핫 리로드
```

Homebrew로 PostgreSQL·Redis까지 직접 설치해 Docker를 전혀 쓰지 않는 방법은
[docs/LOCAL_SETUP.md §5](docs/LOCAL_SETUP.md)에 있습니다.

### 2-3. 키가 없을 때의 동작 (구버전과 동일)

| 키 | 없으면 |
|---|---|
| `FRED_API_KEY` | FRED 웹 CSV로 폴백 (대부분 정상 동작) |
| `KRX_API_KEY` | KRX 선물이 KODEX 200 기반 **추정치**로 폴백 (`isEstimated=true` 표시) |
| `KIS_APP_KEY` / `KIS_APP_SECRET` | 장중 수급 가집계와 **교차 검증** 비활성화 |
| `LS_APP_KEY` / `LS_APP_SECRET` | 수급 레이더 폴백 체인에서 LS 단계만 건너뜀 |
| `TOSS_*` | 토스 연결 테스트 메뉴만 비활성화 |
| AI 키 (`NVIDIA` / `CEREBRAS` / `CLOUDFLARE`) | AI 리포트·연결 테스트 메뉴만 비활성화 |
| `SEC_USER_AGENT` | **키가 아니라 본인 이메일입니다.** 없으면 13F 수집만 멈춥니다 (구버전 `.streamlit/secrets.toml`의 `[sec] user_agent`와 같은 값) |
| `KRX_ID` / `KRX_PW` | KRX 홈페이지 계정(선택). 없으면 pykrx를 비로그인으로 쓰고, KRX가 막으면 레이더의 pykrx 단계만 건너뜁니다 |
| `AI_TIMEOUT_SECONDS` | **엔진을 직접 고른 경우**의 응답 대기 한도(기본 240초). 추론형 모델이 자주 끊기면 늘리세요 |
| `AI_AUTO_ATTEMPT_SECONDS` | `⚡ 자동 탐색`이 엔진 하나를 기다리는 한도(기본 60초). 넘으면 다음 엔진으로 넘어갑니다 |
| `AI_AUTO_BUDGET_SECONDS` | `⚡ 자동 탐색` 전체 시간 예산(기본 180초). 넘으면 남은 엔진을 시도하지 않고 실패 경로를 보여 줍니다 |

> **`SEC_USER_AGENT`는 영문 이메일이어야 합니다.** HTTP 헤더는 latin-1로만
> 보낼 수 있어 한글이 섞이면 요청 자체가 불가능합니다. 예시 값
> (`your-name@example.com`)을 그대로 두는 것도 막습니다 — SEC는 연락이 닿지
> 않는 요청을 차단합니다. 잘못된 값은 **요청을 보내기 전에** 무엇을 고칠지
> 알려 주고 멈춥니다.
>
> ```ini
> SEC_USER_AGENT=hong@naver.com          # ✅
> SEC_USER_AGENT=이메일@이메일.com        # ❌ 한글 불가
> SEC_USER_AGENT=your-name@example.com   # ❌ 예시 값
> ```

---

## 3. 화면 구성 (12개 메뉴 — 구버전과 동일)

| 메뉴 | 경로 | 내용 |
|---|---|---|
| 📊 거시경제 매크로 지표 | `/macro` | **📋 전체 대시보드 원본 데이터 보기/복사**(상단), 환율·국채·원자재·지수, 10Y−2Y·30Y−2Y 금리차(공식 확정치 + 스크래핑 시세 병기), 신용·변동성 리스크, **심화 지표 5종** (차트 기간 1~10년 선택) |
| 🏢 연준 순유동성 트래커 | `/liquidity` | WALCL − TGA − ON RRP, 구성 항목 분해(자릿수가 달라 패널·단위를 분리), 4주/12주 모멘텀 |
| 🔄 섹터 & 자산군 로테이션 | `/sector` | S&P 11개 섹터 + 자산군 수익률·순위·벤치마크 대비 초과성과 |
| 📑 기관 13F 포트폴리오 | `/institutions` | 기관별 분기 보유 종목, 분기 대비 액션, 비중 추이 |
| 🎯 기관 13F Money 교집합 | `/consensus` | 여러 기관 공통 보유·동시 매수/매도 집중도 |
| 🏛️ 글로벌 투기세력 (COT) | `/cot` | CFTC 비상업·상업·비보고 순포지션 6개 자산 |
| 🇰🇷 국내 파생 & 투기세력 | `/krx` | KOSPI200 선물 OI·베이시스·4대 국면·한국판 COT Index·장중 수급 가속도 |
| 📡 외국인/기관 수급 레이더 | `/radar` | 투자자별 순매수 상위, 소스 진단, 누적 이력 |
| 🗄️ 데이터 저장소 상태 | `/status` | 수집 현황·신선도·실패 원인·누락 데이터셋·**교차 검증** |
| 🤖 AI 종합 데이터 분석 | `/ai/report` | 수집 데이터 기반 AI 리포트 (리포트·원본 텍스트 복사, 생성 경과 시간 표시) |
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

### 4-2. 차트는 축부터 정직해야 합니다

면적 차트의 Y축을 늘 0부터 그리면, 0 근처에서 움직이지 않는 계열은 변동이
사라집니다. 순유동성(5.85~6.0조 달러)에 0~8조 축을 쓰면 읽어야 할 움직임이
축 꼭대기 얇은 띠에 눌려 직선처럼 보였습니다. 0이 의미를 갖는 계열(스프레드
처럼 부호가 중요한 값)만 0을 포함시키고, 나머지는 데이터 범위에 맞춥니다.
반대로 값이 음수가 될 수 없는 계열(역레포 등)은 축을 0 아래로 내리지
않습니다 — 있을 수 없는 값을 있을 수 있는 것처럼 보여 주게 됩니다.

**자릿수가 다른 계열을 한 축에 겹치지 않습니다.** 총자산 6.7조 · 재무부 계정
0.88조 · 역레포 0.005조를 한 축에 그리면 아래 둘이 바닥에 눌려 직선이 됩니다.
축을 둘로 나누는 것(이중 축)은 두 축의 정렬이 임의라 **없는 상관을 만들어
냅니다.** 그래서 패널을 나눕니다 — 각자 제 범위를 갖고 시간축만 공유합니다.

**같은 화면의 숫자 타일과 차트는 같은 단위를 씁니다.** 패널을 나눠 놓고도
타일은 "5.2 십억 달러", 차트 눈금은 "0.01T"로 적으면 읽는 사람이 머릿속에서
단위를 환산해야 합니다. 게다가 역레포 실제 수준(약 0.005조)에서는 소수 두
자리 눈금이 전부 `0.00T`이 되어, 패널을 나눈 목적이 그대로 사라집니다.
총자산은 조 달러(T), TGA·RRP는 십억 달러(B)로 각 패널 제목 옆에 단위를 적습니다.

**선 하나로 판정까지 전하지 않습니다.** 0선은 "0이 여기"까지만 말합니다.
"지금 역전 상태"라는 판정은 차트 위 배너 한 줄이 글로 적습니다(색·선만으로
의미를 전달하지 않기 위해서입니다). 금리차 카드와 심화 매크로의
T10Y3M·NFCI가 그렇습니다.

**기간 선택은 저장된 구간을 넘지 않습니다.** 수집기는 FRED에서 10년 + 90일을
받아 둡니다(`period_years=10`). 그래서 기간 탭은 1·3·5·10년까지만 둡니다.
"전체" 탭을 만들면 10년과 똑같은 그림이 나오면서, 읽는 사람은 1982년부터의
T10Y3M을 보고 있다고 오해합니다. 더 긴 구간이 필요하면 탭이 아니라 **수집
구간**부터 늘려야 합니다.

계열 색은 눈대중으로 고르지 않고 검증기(dataviz `validate_palette.js`)를
돌립니다. 이전 조합은 적록색약에서 노랑↔빨강 ΔE가 5.8로 구분 한계(6) 아래라,
두 선이 실제로 겹쳐 보였습니다.

### 4-3. 추정치는 추정치라고 말합니다

| 값 | 표시 |
|---|---|
| KRX 수집 실패 시 KODEX 200 기반 선물 가격 | `isEstimated=true` + 화면 경고 배너 |
| MOVE 지수 (Yahoo가 제공하지 않음) | `isProxy=true` + "실제 ICE BofA MOVE 아님" |

추정치는 **누적 이력 테이블에 쓰지 않습니다.** 한 번 섞이면 실제 확정치와
구분할 수 없습니다.

### 4-4. 모르면 "판정 불가"입니다

KOSPI200 선물 4대 국면은 등락률과 미결제약정 증감이 모두 있어야 판정합니다.
구버전은 결측을 0.0으로 메워 `등락률 >= 0`이 항상 참이 되었고, **하락한 날에도
'신규 롱'(강세)** 으로 표시됐습니다(2026-09-11: 실제 −2.13%). 등락률은 이제
연속된 확정 종가에서 직접 계산하고, KRX 보고값은 대조용으로만 남깁니다.

### 4-5. "확인 못 함"과 "일치"를 섞지 않습니다

교차 검증 판정은 네 가지입니다: **일치 / 불일치 / 수집 실패 / 확인 못 함**.
키가 없어 비교하지 못한 것을 "일치"로 표시하면 검증 자체가 거짓말이 됩니다.

시간 조건이 항목마다 반대인 것도 그대로입니다 — KRX는 *일별 확정 종가*, KIS는
*현재가*를 주므로 시세 대조는 **장 마감 후**에만, KIS 수급 가집계 TR은 장중
전용이라 수급 대조는 **정규장 중**에만 가능합니다.

**기준일이 다르면 비교하지 않습니다.** 시간대가 맞아도 KRX 확정치는 하루 이상
늦게 올라오는 반면 KIS는 최신값을 줍니다. 이때 그냥 비교하면 "불일치"가 뜨는데,
값이 갈라진 게 아니라 **서로 다른 날을 본 것**입니다. 읽기값마다 기준 거래일을
달고, 날짜가 어긋나면 "확인 못 함"으로 둡니다. 이 검사가 없으면 KRX가 밀리는
날마다 거짓 경보가 울리고 그 사이 진짜 불일치가 묻힙니다.

### 4-6. 화면은 수집을 기다리지 않습니다

읽기 모드(`DASHBOARD_READ_MODE`)는 구버전을 그대로 계승합니다.

| 값 | 동작 |
|---|---|
| `auto` (기본) | 저장본이 신선하면 사용, 오래되면 수집기에 수집 요청 |
| `store_only` | 저장본만 사용. 오래돼도 그대로 보여주고 외부를 **절대** 기다리지 않음 |
| `live_only` | 항상 수집 요청 (디버깅용) |

**저장본이 있으면 절대 기다리지 않습니다.** 오래된 저장본을 발견하면 수집을
요청만 해 두고(비동기) 화면에는 기존 값을 즉시 보여 줍니다. 각 메뉴 오른쪽 위의
신선도 배지가 **수집한 시각**(`🕒 수집 20:55:59 KST`, 어제 이전이면 날짜까지)을
그대로 말해 주고, 수집이 끝나면 다음 조회에서 새 값이 나옵니다. 예전에는 수집이 끝날 때까지 붙잡고 있어서 화면 한 번 여는 데
`sec_13f` 31.8초 · `fred_series` 11.5초가 그대로 대기 시간이 됐습니다.
보여 줄 값이 아예 없을 때만 기다립니다 — 빈 화면보다는 낫기 때문입니다.

같은 태스크 재요청은 30초 동안 억제합니다. 화면 하나가 스냅샷을 병렬로 읽으면
같은 요청이 그만큼 나가는데(실제로 한 번의 페이지 로드에 `fred_series` 요청이
7건이었습니다), 수집기가 합쳐 주기는 해도 애초에 보내지 않는 편이 낫습니다.

**수동 새로고침도 분기 공시까지 다시 받지는 않습니다.** 새로고침은 "이 시각
이전 저장본은 낡은 것으로 본다"는 기준을 세우는데, 그 기준이 데이터셋 종류를
가리지 않으면 버튼 한 번에 13F 전수 수집이 다시 돕니다(실제로 5분 사이에 SEC
수집이 세 번 돌았습니다). SEC·FRED는 호출 한도를 명시하고 초과하면 차단합니다.
데이터셋 종류별로 최소 재수집 간격을 둡니다 — 시세성 1분, 일별 확정치 30분,
분기 공시 6시간. 빠르게 바뀌는 데이터는 거의 제한하지 않습니다(새로고침을
누르는 이유가 대개 그쪽입니다).

수동 새로고침은 저장본을 **지우지 않습니다.** "이 시각 이전 저장본은 낡은
것으로 본다"는 기준만 세웁니다 — 수집이 실패하면 보여 줄 값이 아예 없어지기
때문입니다.

버튼을 누르면 수집을 요청하고 **끝날 때까지 기다렸다가 화면이 스스로
갱신됩니다.** 기다리는 동안 버튼은 `수집 중… 끝나면 자동 갱신`으로 바뀝니다.
수집은 백그라운드로 돌기 때문에, 요청 직후에 다시 읽으면 아직 예전 값입니다.
수집기가 새 실행 번호를 남기고 끝났는지를 보고 갱신 시점을 정합니다.

### 4-7. 로그에 비밀값을 남기지 않습니다

API 키가 로그로 새어 나간 적이 있습니다. FRED 호출이 느려 urllib3가 재시도
경고를 찍었는데, 그 경고에 **요청 URL 전체**가 들어 있었습니다.

```
WARNING urllib3.connectionpool: Retrying (...) after connection broken by
'ReadTimeoutError(...)': /fred/series/observations?series_id=WALCL
&api_key=deadbeefdeadbeefdeadbeefdeadbeef&file_type=json&...
```

URL을 찍는 쪽이 서드파티 라이브러리라 우리 코드만 조심해서는 막을 수
없습니다. **로깅 계층에서** 가립니다(`collector/app/logredact.py`) — 어떤
코드가 찍든 출력 직전에 한 곳에서 걸립니다. `api_key`·`auth_key`·`appkey`·
`app_secret`·`access_token`·`password`와 `Bearer` 토큰이 대상입니다.
진단에 필요한 나머지(`series_id=WALCL` 등)는 그대로 남깁니다.

> 위 예시의 키 값은 **실제 키가 아닌 자리 표시자**(`deadbeef…`)입니다.
> 문서·테스트·주석에는 진짜 키를 적지 않습니다. 한 번 커밋되면 나중에 지워도
> 커밋 이력에 그대로 남기 때문입니다.

**이미 새어 나갔다면 가리는 것으로는 부족합니다 — 재발급하세요.** 로그·터미널
기록·스크린샷·커밋 이력 어디에든 한 번 찍혔다면 그 키는 더 이상 비밀이
아닙니다. 발급처에서 새 키를 받고, `.env`의 값을 바꾼 뒤 `make up`으로 컨테이너를
다시 올리면 됩니다(코드 수정은 필요 없습니다).

| 키 | 재발급 위치 |
|---|---|
| `FRED_API_KEY` | <https://fredaccount.stlouisfed.org/apikeys> |
| `KRX_API_KEY` | KRX OPEN API 마이페이지 |
| `KIS_APP_KEY` / `KIS_APP_SECRET` | 한국투자증권 KIS Developers |
| `LS_APP_KEY` / `LS_APP_SECRET` | LS증권 OPEN API |

### 4-8. 한 출처에는 한 가지 방법으로만 붙습니다

같은 출처에 두 가지 방식으로 붙으면 한쪽만 조용히 막히고, 화면에는 설명할 수
없는 상태가 남습니다. 실제로 그랬습니다 — 같은 시각 같은 종목인데 매크로
카드(yfinance)는 `WTI 102.47`, 스크래핑 비교표(생 requests)는
`429 Too Many Requests` 였습니다. 데이터가 아니라 **클라이언트**가 달랐던
것입니다. yfinance는 Yahoo가 요구하는 쿠키·crumb를 관리하지만 생 호출은
그 처리가 없어 먼저 차단됩니다.

Yahoo로 가는 길은 `services/market.py` 하나로 모읍니다. 테스트가 `query1…`
직접 호출이 다시 들어오는 것을 막습니다.

### 4-9. 같은 수집을 동시에 두 번 돌리지 않습니다

화면 하나를 여는 것만으로 같은 태스크가 여러 번 돌 수 있습니다. 백엔드는
스냅샷을 읽다가 오래됐으면 수집을 요청하는데, 한 화면이 여러 스냅샷을 병렬로
읽으면 그 요청이 각각 나가기 때문입니다. 실제 로그에서 `cot_history`가 같은
초에 네 번, `fred_series`가 세 번 동시에 돌았습니다. 외부 API 호출이 그대로
몇 배가 되고(FRED·SEC는 호출 한도가 있습니다) 같은 행을 동시에 쓰게 됩니다.

수집기는 이미 도는 태스크가 있으면 **새로 시작하지 않고 그 결과를 기다렸다가
함께 씁니다.** 뒤늦게 온 호출을 거절하지 않는 이유는, 호출자가 원하는 것이
"지금 새로 받아라"가 아니라 "신선한 값을 달라"이기 때문입니다.

### 4-10. 과거 조회가 안 되는 소스는 우리가 이력을 쌓습니다

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

저장소 최상위에서 `make`로 부르는 편이 안전합니다(가상환경·DB 주소를 알아서 맞춥니다).

```bash
make test              # 세 가지 전부
make test-collector    # 수집기만
make test-backend      # 백엔드만
make test-frontend     # 화면만
```

직접 부르려면 — 각 블록은 **최상위에서 새로 시작**한다고 보고 경로를 적었습니다.

```bash
# 수집기 (PostgreSQL 필요 — 없으면 저장 계층 테스트만 자동 건너뜀)
(cd collector && TEST_DATABASE_URL=postgresql://macro:macro@localhost:5432/macrodash \
  python -m pytest tests -q)

# 백엔드 (통합 테스트가 실제 DB를 사용 · Java 21 필요)
(cd backend && TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/macrodash mvn verify)

# 화면 (타입 검사 포함)
(cd frontend && npm run lint && npm run build)
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
- `collector/tests/test_failure_reasons.py` — 수집 0건일 때 **왜**가 남는지,
  사유 중복 합치기, SEC 연락처 형식 검증(한글·예시 값 차단)
- `collector/tests/test_task_coalescing.py` — 같은 태스크가 동시에 두 번 돌지
  않는지, 기다린 쪽도 결과·실패 사유를 받는지, 끝난 뒤엔 다시 돌 수 있는지
- `collector/tests/test_scraper_yahoo_path.py` — Yahoo 데이터 호출이 한 경로로
  모여 있는지, 코스피200 야간선물의 KODEX 폴백이 추정치로 표시되는지,
  Naver 빈 결과의 원인을 구분해 말하는지, bonds scanner를 필요할 때만 부르는지
- `collector/tests/test_log_redaction.py` — 로그에서 API 키·Bearer 토큰이
  가려지는지, 라이브러리가 인자로 넘긴 URL까지 걸리는지, 비밀이 아닌 값은
  건드리지 않는지
- `backend/.../StoreReaderNonBlockingTest.java` — **화면이 수집을 기다리지
  않는지**, 저장본이 없을 때만 기다리는지, 같은 태스크 재요청을 억제하는지,
  수동 새로고침이 방금 받은 분기 공시를 다시 받지 않는지
- `backend/.../VerificationTest.java` — 판정 네 가지 구분, 장 시간 게이트,
  **기준일이 다르면 불일치로 세지 않기**
- `backend/.../SeriesMathTest.java` — 표본 부족이 0.0이 아니라 null인지
- `backend/.../Sec13FServiceTest.java` — 분기 대비 액션 분류, CUSIP 문자열 보존,
  `shares`가 빠진 보유 항목, 모르는 것을 '신규 매수'로 단정하지 않기
- `backend/.../AiServiceContentTypeTest.java` — `application/octet-stream` 응답
  파싱과 한국어 왕복, 실패 시 서버 본문·상태코드를 그대로 보여주기
- `backend/.../SnapshotFreshnessTest.java` — "언제 수집한 값인지"를 항상 함께
  담기, 수집 시각을 모를 때 경과 시간을 지어내지 않기
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

**먼저 `make doctor`를 실행하세요.** 컨테이너 → 수집기 → DB → 백엔드 → 화면
순서로 검사하고 *가장 먼저 고칠 것 한 가지*를 알려 줍니다.

| 증상 | 원인 / 해결 |
|---|---|
| `make collect`는 성공인데 `make status`가 `0/12 시리즈`처럼 비어 있음 | 수집기는 돌았지만 외부 소스가 데이터를 주지 않았습니다. `(사유: …)` 문구를 함께 출력하니 그것부터 보세요 |
| 사유가 `yfinance(…)가 빈 응답을 받았습니다` | yfinance가 낡으면 Yahoo 응답 변경에 대응하지 못합니다. `make update && make up`(재빌드) |
| 사유가 `CSV HTTP 403` | FRED 웹 CSV 차단입니다. `.env`에 무료 `FRED_API_KEY`를 넣으면 공식 API로 우회합니다 |
| 13F가 `SEC_USER_AGENT가 설정되지 않았습니다` | `open -e .env` → `SEC_USER_AGENT=본인이메일` → `make up`. 키가 아니라 연락처입니다 |
| 13F가 `영문/숫자가 아닌 문자가 있습니다` | `SEC_USER_AGENT`에 한글이 들어갔습니다. HTTP 헤더는 한글을 담을 수 없습니다 |
| AI 리포트가 `N초 안에 응답하지 않았습니다` | 추론형 모델은 오래 걸립니다. `⚡ 자동 탐색`을 고르거나 `.env`의 `AI_TIMEOUT_SECONDS`를 늘리세요 |
| AI 리포트 생성이 너무 오래 걸림 | `⚡ 자동 탐색`은 빠른 엔진부터 부르고 전체 `AI_AUTO_BUDGET_SECONDS`(기본 180초) 안에 끝냅니다. 엔진 목록에 `매우 느림 — 추론형`이라 적힌 모델을 직접 고르면 그 모델을 끝까지 기다립니다 |
| 복사 버튼을 눌러도 복사가 안 됨 | 다른 기기에서 `http://192.168.x.x:3000`으로 열면 브라우저가 클립보드를 막습니다. 이 경우 화면이 대체 경로로 복사하고, 그것도 막히면 버튼 옆에 사유를 표시합니다 |
| 수집기 로그 첫 줄이 `KRX 로그인 실패: KRX_ID …` | pykrx가 import할 때 찍는 문구입니다. **우리 앱의 오류가 아닙니다** (최신 버전은 우리 로거로 옮겨 뜻이 통하게 적습니다) |
| 스크래핑 비교표에서 WTI·브렌트·상해종합만 `429 Too Many Requests` | Yahoo 데이터 API에 생 requests로 붙던 경로였습니다. 최신 버전은 앱 전체가 쓰는 yfinance 경로 하나로 모읍니다 |
| 로그에 API 키가 보임 | 최신 버전은 로깅 계층에서 가립니다(`api_key=***redacted***`). 예전 로그에 남았다면 **해당 키를 재발급**하세요 |
| 수급 레이더 진단의 `NAVER 빈 결과` | 최신 버전은 원인을 구분해 말합니다 — "표가 없습니다"(차단·JS 요구)와 "종목 행이 없습니다"(휴장·구조 변경) |
| 같은 태스크가 로그에 여러 번 시작됨 | 최신 버전은 이미 도는 수집이 있으면 새로 시작하지 않고 그 결과를 함께 씁니다 |
| `.env` 첫 줄이 `# .env.example …`이라 잘못 저장한 것 같음 | **정상입니다.** `make setup`이 `.env.example`을 복사해 만들기 때문입니다(최신 버전은 머리말을 `.env`로 바꿔 줍니다). `KEY=VALUE` 줄만 맞으면 됩니다 |
| `.env` 값에 따옴표를 둘렀는데 인식이 이상함 | docker compose는 `KEY=VALUE`를 그대로 읽습니다. `KEY="값"`이면 따옴표까지 값이 됩니다. `export`나 들여쓰기도 안 됩니다 |
| 수동 새로고침을 눌러도 화면이 그대로 | 최신 버전은 수집이 끝나면 **자동으로 갱신**됩니다(`수집 중… 끝나면 자동 갱신` 표시). 그대로라면 `make update && make up` |
| **고쳤다는 기능이 화면에 없음** | 그 코드로 빌드되지 않았습니다. 화면 **왼쪽 아래 버전**(`main@1e4d3be`)과 `make version`을 보세요. 새 작업이 다른 브랜치에 있으면 `git pull`은 아무것도 받지 않습니다 — `make update`를 쓰세요 |
| `make update`가 `수정 중인 파일이 있어 당기지 않았습니다` | 고쳐 둔 파일이 있어 덮어쓰지 않은 것입니다. `git status`로 확인 후 `git restore <파일>`(버리기) 또는 `git stash`(보관) |
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

- [docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md) — **맥 로컬 설치·실행 상세** (포트 변경, launchd, 문제 해결)
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
