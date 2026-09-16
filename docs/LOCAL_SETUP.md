# 로컬 설치·실행 가이드 (macOS)

이 문서는 맥에서 `~/Projects/Local-macro-dashboard-v2`에 프로젝트를 두고 쓰는
것을 기준으로 씁니다. 다른 경로를 쓰면 아래 명령의 경로만 바꾸면 됩니다.

> 구버전은 `~/Projects/macro-dashboard-v2`에서 `venv` + `streamlit run app.py`로
> 돌렸습니다. v2는 컨테이너 다섯 개(화면·API·수집기·DB·캐시)라 **Docker로
> 묶어 한 줄로 띄우는 방식**이 기본이고, Docker 없이 쓰는 경로도 함께 둡니다.

---

## 0. 한눈에 보기

```bash
# 저장
git clone https://github.com/Jinyoung-Kang/Local_macro_dashboard_v2.git \
  ~/Projects/Local-macro-dashboard-v2
cd ~/Projects/Local-macro-dashboard-v2

# 준비 (.env 생성 · 키/포트 확인)
make setup

# 실행
make up          # 최초 빌드 5~10분
make collect     # 첫 데이터 수집 (이걸 해야 화면에 숫자가 찹니다)
open http://localhost:3000
```

> **저장소 이름과 폴더 이름이 다릅니다.** GitHub 저장소는 밑줄
> (`Local_macro_dashboard_v2`), 로컬 폴더는 하이픈
> (`Local-macro-dashboard-v2`)입니다. 그래서 `git clone` 뒤에 **폴더 경로를
> 반드시 직접 지정**해야 합니다. 생략하면 `Local_macro_dashboard_v2` 폴더가
> 만들어집니다.

---

## 1. 사전 준비

### 1-1. Docker Desktop (권장 경로)

```bash
brew install --cask docker
open -a Docker          # 처음 실행 시 권한을 한 번 물어봅니다
docker info             # 데몬이 떠 있으면 정보가 출력됩니다
```

Apple Silicon(M1~M4)에서 그대로 동작합니다. 이 프로젝트가 쓰는 이미지
(`postgres:16-alpine`, `redis:7-alpine`, `eclipse-temurin:21`, `node:22-alpine`)는
모두 arm64 빌드를 제공합니다.

**Docker Desktop 설정 권장값** — 설정 → Resources
- 메모리 **4GB 이상** (백엔드 빌드에 JVM이 돌아갑니다)
- 디스크 10GB 이상

### 1-2. Docker를 쓰지 않는 경우

Homebrew로 직접 설치합니다.

```bash
brew install postgresql@16 redis maven node@22 python@3.11
brew install --cask temurin@21          # Java 21 (JDK)

brew services start postgresql@16
brew services start redis
```

Python은 **3.11 또는 3.12**를 권합니다. 3.13에서는 `pandas`/`pykrx` 계열
휠이 아직 준비되지 않아 소스 빌드로 넘어가는 경우가 있습니다.
(확인하지 못한 조합이라 단정하지는 않겠습니다 — 3.11에서 검증했습니다.)

---

## 2. 저장 (clone)

```bash
mkdir -p ~/Projects
git clone https://github.com/Jinyoung-Kang/Local_macro_dashboard_v2.git \
  ~/Projects/Local-macro-dashboard-v2

cd ~/Projects/Local-macro-dashboard-v2
ls          # README.md · docker-compose.yml · Makefile · backend · collector · frontend
```

이미 받아 둔 폴더를 최신으로 맞출 때:

```bash
cd ~/Projects/Local-macro-dashboard-v2
git pull origin main
```

---

## 3. 준비 (`make setup`)

```bash
make setup
```

이 스크립트가 하는 일:

1. `.env.example`을 복사해 `.env`를 만듭니다 (이미 있으면 건드리지 않습니다)
2. `JWT_SECRET`을 무작위 값으로 생성합니다 — 기본 placeholder를 그대로 두면
   누구나 세션 토큰을 위조할 수 있습니다
3. API 키 보유 현황과 "없을 때 무엇이 꺼지는지"를 알려 줍니다
4. Docker / Java / Node / Python 설치 여부를 확인합니다
5. 포트 5개(3000·8080·8000·5432·6379)가 비어 있는지 확인합니다

그다음 `.env`를 열어 **접속 비밀번호**부터 바꾸세요.

```bash
open -e .env            # 또는 code .env / vi .env
```

```ini
APP_PASSWORD=원하는_비밀번호
```

### API 키 (없어도 실행됩니다)

| 키 | 발급처 | 없으면 |
|---|---|---|
| `FRED_API_KEY` | <https://fred.stlouisfed.org/docs/api/api_key.html> | 웹 CSV로 폴백 (대부분 정상) |
| `KRX_API_KEY` | <http://data.krx.co.kr> | KRX 선물이 KODEX 200 기반 **추정치** |
| `KIS_APP_KEY` / `KIS_APP_SECRET` | <https://apiportal.koreainvestment.com> | 장중 수급 가집계·교차 검증 꺼짐 |
| `LS_APP_KEY` / `LS_APP_SECRET` | LS증권 홈 > 매매시스템 > API | 수급 레이더의 LS 단계만 건너뜀 |
| `TOSS_CLIENT_ID` / `TOSS_CLIENT_SECRET` | 토스증권 Open API | 토스 테스트 메뉴만 꺼짐 |
| `NVIDIA_API_KEY` 등 AI 키 | <https://build.nvidia.com> 등 | AI 메뉴만 꺼짐 |

> 구버전의 `.streamlit/secrets.toml`은 더 이상 쓰지 않습니다. 같은 값을
> `.env`에 넣으면 됩니다(`[fred] api_key` → `FRED_API_KEY`).

---

## 4. 실행 (Docker)

```bash
make up
```

최초 실행은 이미지 빌드 때문에 **5~10분** 걸립니다(백엔드 Maven 의존성,
프런트 npm 설치). 이후에는 30초 내외입니다.

| 주소 | 용도 |
|---|---|
| <http://localhost:3000> | 화면 (로그인 → `.env`의 `APP_PASSWORD`) |
| <http://localhost:8080/api/health> | 백엔드 상태 |
| <http://localhost:8000/docs> | 수집기 API 문서 (FastAPI 자동 생성) |

### 첫 데이터 수집

컨테이너가 떴다고 숫자가 바로 차지는 않습니다. 수집기가 한 번 돌아야 합니다.

```bash
make collect        # 시세·수급 (fast) — 약 1분
make collect-all    # 13F 포함 전체 — 10분 이상, 백그라운드 실행
make status         # 진행 상황
```

그 뒤로는 컨테이너 안의 스케줄러가 **5분 / 1시간 / 12시간** 주기로 알아서
수집합니다(구버전 `collector.py --loop`과 같은 주기).

### 자주 쓰는 명령

```bash
make ps                 # 컨테이너 상태
make logs               # 전체 로그
make logs S=collector   # 수집기 로그만
make down               # 정지 (데이터 보존)
make up                 # 다시 시작
make backup             # DB 백업 → backups/
```

### 맥 재부팅 후

`restart: unless-stopped`가 걸려 있어 **Docker Desktop이 시작되면 컨테이너도
자동으로 올라옵니다.** Docker Desktop 자체를 로그인 시 자동 실행하려면
설정 → General → "Start Docker Desktop when you sign in"을 켜세요.

---

## 5. 실행 (Docker 없이 — 네이티브)

터미널 4개를 씁니다. 구버전이 터미널 2개(수집기·화면)를 쓰던 것에서 서비스가
늘어난 만큼 창도 늘었습니다.

```bash
# --- 터미널 0: DB 준비 (최초 1회) ---
createdb -U $(whoami) macrodash 2>/dev/null || true
psql -d macrodash -f db/migrations/V1__init.sql

# --- 터미널 1: 수집기 ---
cd ~/Projects/Local-macro-dashboard-v2/collector
python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export $(grep -v '^#' ../.env | grep -E '^(FRED|KRX|KIS|LS|TOSS)_' | xargs)
DATABASE_URL="postgresql://$(whoami)@localhost:5432/macrodash" \
  uvicorn app.main:app --reload --port 8000

# --- 터미널 2: 백엔드 ---
cd ~/Projects/Local-macro-dashboard-v2/backend
DATABASE_URL="jdbc:postgresql://localhost:5432/macrodash" \
DATABASE_USER="$(whoami)" DATABASE_PASSWORD="" \
APP_PASSWORD="원하는_비밀번호" \
COLLECTOR_URL="http://localhost:8000" \
  mvn spring-boot:run

# --- 터미널 3: 화면 ---
cd ~/Projects/Local-macro-dashboard-v2/frontend
npm install
NEXT_PUBLIC_API_BASE=http://localhost:8080 npm run dev
```

Redis가 없으면 백엔드가 캐시를 쓰지 못해 기동에 실패할 수 있습니다. Redis를
띄우지 않으려면 캐시를 꺼서 실행하세요.

```bash
CACHE_TYPE=none mvn spring-boot:run
```

### 수집기를 백그라운드 상주로 (launchd)

구버전의 `collector.py --install-launchd`에 해당합니다. 맥이 켜져 있는 동안
수집기가 계속 돌게 하려면 아래 plist를
`~/Library/LaunchAgents/com.local.macro-dashboard.collector.plist`로 저장하고
등록하세요.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.local.macro-dashboard.collector</string>

  <key>ProgramArguments</key>
  <array>
    <string>/Users/jinyoung/Projects/Local-macro-dashboard-v2/collector/.venv/bin/uvicorn</string>
    <string>app.main:app</string>
    <string>--host</string><string>127.0.0.1</string>
    <string>--port</string><string>8000</string>
  </array>

  <key>WorkingDirectory</key>
  <string>/Users/jinyoung/Projects/Local-macro-dashboard-v2/collector</string>

  <key>EnvironmentVariables</key>
  <dict>
    <key>DATABASE_URL</key>
    <string>postgresql://jinyoung@localhost:5432/macrodash</string>
    <key>COLLECTOR_SCHEDULER</key>
    <string>true</string>
  </dict>

  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>

  <key>StandardOutPath</key>
  <string>/Users/jinyoung/Projects/Local-macro-dashboard-v2/collector.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/jinyoung/Projects/Local-macro-dashboard-v2/collector.log</string>
</dict>
</plist>
```

```bash
launchctl load -w ~/Library/LaunchAgents/com.local.macro-dashboard.collector.plist
tail -f ~/Projects/Local-macro-dashboard-v2/collector.log

# 해제
launchctl unload -w ~/Library/LaunchAgents/com.local.macro-dashboard.collector.plist
```

> Docker로 실행 중이라면 이 설정은 필요 없습니다. 컨테이너가 같은 일을 합니다.

---

## 6. 확인 · 테스트

```bash
make test              # 세 언어 전부 (PostgreSQL 필요)
make test-collector    # pytest 51건
make test-backend      # JUnit 42건
make test-frontend     # 린트 + 빌드
```

`make test`는 `localhost:5432`의 PostgreSQL을 씁니다. Docker로 띄운 상태라면
그대로 돌아가고, 아니면 `make infra`로 DB·Redis만 먼저 올리세요.

---

## 7. 맥에서 자주 걸리는 문제

| 증상 | 원인 / 해결 |
|---|---|
| `git clone` 후 폴더 이름이 `Local_macro_dashboard_v2` | clone 뒤에 목적지 경로를 지정하지 않았습니다. `mv Local_macro_dashboard_v2 Local-macro-dashboard-v2` |
| `Cannot connect to the Docker daemon` | Docker Desktop이 꺼져 있습니다. `open -a Docker` 후 30초 |
| 포트 5432 충돌 | 맥에 PostgreSQL이 이미 돌고 있습니다. `.env`의 `DATABASE_PORT=5433`으로 바꾸고 `make up` |
| 포트 3000 충돌 | 다른 개발 서버가 씁니다. `.env`에서 `FRONTEND_PORT=3001`, `NEXT_PUBLIC_API_BASE`는 그대로, `FRONTEND_ORIGIN=http://localhost:3001` |
| 로그인은 되는데 화면이 계속 401 | `127.0.0.1`로 접속했는데 `FRONTEND_ORIGIN`은 `localhost`입니다(또는 반대). 브라우저는 둘을 **다른 오리진**으로 보고 쿠키를 막습니다. 주소를 하나로 통일하세요 |
| 화면은 뜨는데 전부 "데이터 없음" | 아직 수집을 하지 않았습니다. `make collect` |
| `make collect`가 "수집기에 연결하지 못했습니다" | `make logs S=collector`로 기동 여부 확인 |
| 백엔드 빌드가 메모리 부족으로 죽음 | Docker Desktop 메모리를 4GB 이상으로 올리세요 |
| 수집은 성공인데 숫자가 이상함 | `🗄️ 데이터 저장소 상태 → 교차 검증`을 돌려 보세요. 비공식 소스(Daum·Naver·TradingView)의 구조 변경을 먼저 의심합니다 |
| 포트를 바꿨는데 화면이 API를 못 찾음 | `NEXT_PUBLIC_API_BASE`는 **빌드 시점**에 번들에 들어갑니다. 바꾼 뒤 `make up`(재빌드)이 필요합니다 |

---

## 8. 백업

누적 수급 이력(`observations`)은 **외부에서 다시 받을 수 없는 데이터**입니다.
Naver·Daum·KRX가 과거 날짜 조회를 지원하지 않기 때문입니다.

```bash
make backup                              # backups/macrodash-YYYYmmdd-HHMMSS.sql
make restore F=backups/macrodash-….sql   # 복원
```

Time Machine을 쓴다면 `~/Projects/Local-macro-dashboard-v2/backups`가 백업
대상에 포함되는지 확인해 두세요. Docker 볼륨 자체(`postgres-data`)는 Time
Machine이 온전히 담지 못할 수 있어, SQL 덤프를 남기는 편이 안전합니다.
