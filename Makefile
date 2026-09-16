# =============================================================================
# Makefile — 자주 쓰는 명령 모음
#
#   make setup    최초 1회 준비 (.env 생성 · 키 확인 · 포트 확인)
#   make up       전체 스택 기동
#   make collect  지금 데이터 수집 (첫 실행 후 반드시 한 번)
#   make status   수집 현황 확인
#   make down     정지
#
# Docker 없이 쓰는 개발 명령은 make dev-* 를 보세요.
# =============================================================================

SHELL := /bin/bash
COMPOSE := docker compose

# .env가 있으면 포트 설정을 읽어 안내 문구에 씁니다(없으면 기본값).
FRONTEND_PORT ?= $(shell grep -s '^FRONTEND_PORT=' .env | cut -d= -f2)
FRONTEND_PORT := $(if $(FRONTEND_PORT),$(FRONTEND_PORT),3000)
BACKEND_PORT ?= $(shell grep -s '^BACKEND_PORT=' .env | cut -d= -f2)
BACKEND_PORT := $(if $(BACKEND_PORT),$(BACKEND_PORT),8080)
COLLECTOR_PORT ?= $(shell grep -s '^COLLECTOR_PORT=' .env | cut -d= -f2)
COLLECTOR_PORT := $(if $(COLLECTOR_PORT),$(COLLECTOR_PORT),8000)

.DEFAULT_GOAL := help
.PHONY: help setup up down restart logs ps collect collect-all status verify \
        doctor test test-collector test-backend test-frontend \
        dev-collector dev-backend dev-frontend db infra backup restore reset

# 네이티브 개발용 파이썬. collector/.venv가 있으면 그것을 씁니다.
# (conda base 같은 다른 파이썬이 PATH 앞에 있으면 pytest·uvicorn을
#  "모듈 없음"으로 실패시킵니다. 실제로 겪은 오류입니다.)
VENV_PY := collector/.venv/bin/python
PY := $(shell test -x $(VENV_PY) && echo $(VENV_PY) || echo python3)

help: ## 사용 가능한 명령 목록
	@echo ""
	@echo "Local Macro Dashboard v2"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ------------------------------------------------------------------ 준비·기동
setup: ## 최초 1회 준비 (.env 생성 · 키/포트 확인)
	@bash scripts/setup.sh

up: ## 전체 스택 기동 (최초 빌드는 5~10분)
	@test -f .env || (echo "⚠️  .env가 없습니다. 먼저 'make setup'을 실행하세요." && exit 1)
	$(COMPOSE) up -d --build
	@echo ""
	@echo "  화면    : http://localhost:$(FRONTEND_PORT)"
	@echo "  API     : http://localhost:$(BACKEND_PORT)/api/health"
	@echo "  수집기  : http://localhost:$(COLLECTOR_PORT)/status"
	@echo ""
	@echo "  첫 실행이라면 'make collect'로 데이터를 한 번 받아 오세요."
	@echo ""

down: ## 정지 (데이터는 보존)
	$(COMPOSE) down

restart: ## 재기동
	$(COMPOSE) restart

ps: ## 컨테이너 상태
	$(COMPOSE) ps

logs: ## 로그 따라 보기 (make logs S=backend 로 특정 서비스만)
	$(COMPOSE) logs -f --tail=100 $(S)

infra: ## PostgreSQL·Redis만 기동 (네이티브 개발용)
	$(COMPOSE) up -d postgres redis

# ------------------------------------------------------------------ 데이터
collect: ## 시세·수급 수집 (fast — 약 1분)
	@curl -fsS -X POST "http://localhost:$(COLLECTOR_PORT)/collect?group=fast" \
		| python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "수집기에 연결하지 못했습니다."

collect-all: ## 전체 수집 (13F 포함 — 10분 이상)
	@curl -fsS -X POST "http://localhost:$(COLLECTOR_PORT)/collect?group=all&wait=false" \
		| python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "수집기에 연결하지 못했습니다."
	@echo "백그라운드로 실행 중입니다. 'make status'로 진행 상황을 확인하세요."

status: ## 수집 현황 (구버전 collector.py --status)
	@curl -fsS "http://localhost:$(COLLECTOR_PORT)/status" \
		| python3 -c "import json,sys; d=json.load(sys.stdin); \
print('마지막 실행:', d.get('lastRunStatus')); \
print('누락 데이터셋:', len(d.get('missingDatasets') or [])); \
[print(' ', t['status'], t['task'], (t.get('detail') or '')[:60]) for t in (d.get('taskSummary') or [])]" \
		2>/dev/null || echo "수집기에 연결하지 못했습니다."

verify: ## 교차 검증 실행 (KRX·KIS 대조)
	@curl -fsS "http://localhost:$(COLLECTOR_PORT)/verify/readings" \
		| python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "수집기에 연결하지 못했습니다."

# ------------------------------------------------------------------ 진단
doctor: ## 어디가 막혔는지 한 번에 진단 (데이터가 안 보일 때 먼저 실행)
	@bash scripts/doctor.sh

# ------------------------------------------------------------------ 테스트
test: test-collector test-backend test-frontend ## 전체 테스트

test-collector: ## 수집기 테스트 (PostgreSQL 필요)
	@test -x $(VENV_PY) || echo "ℹ️  collector/.venv가 없어 $(PY)로 실행합니다. 'No module named pytest'가 나오면 docs/LOCAL_SETUP.md의 가상환경 절을 보세요."
	cd collector && TEST_DATABASE_URL=$${TEST_DATABASE_URL:-postgresql://macro:macro@localhost:5432/macrodash} \
		$(if $(filter $(VENV_PY),$(PY)),.venv/bin/python,python3) -m pytest tests -q

test-backend: ## 백엔드 테스트 (PostgreSQL 필요)
	cd backend && TEST_DATABASE_URL=$${TEST_DATABASE_URL:-jdbc:postgresql://localhost:5432/macrodash} \
		mvn -B verify

test-frontend: ## 화면 린트 + 빌드(타입 검사 포함)
	cd frontend && npm run lint && npm run build

# ------------------------------------------------------------- 네이티브 개발
dev-collector: ## 수집기 개발 서버 (자동 리로드 · collector/.venv 필요)
	@test -x $(VENV_PY) || (echo "⚠️  collector/.venv가 없습니다. 먼저:" && \
		echo "    python3 -m venv collector/.venv && collector/.venv/bin/pip install -r collector/requirements.txt" && exit 1)
	cd collector && DATABASE_URL=postgresql://macro:macro@localhost:5432/macrodash \
		.venv/bin/python -m uvicorn app.main:app --reload --port $(COLLECTOR_PORT)

dev-backend: ## 백엔드 개발 서버
	cd backend && mvn spring-boot:run

dev-frontend: ## 화면 개발 서버 (핫 리로드)
	cd frontend && NEXT_PUBLIC_API_BASE=http://localhost:$(BACKEND_PORT) npm run dev

db: ## PostgreSQL 셸
	$(COMPOSE) exec postgres psql -U macro -d macrodash

# ------------------------------------------------------------------ 백업·정리
backup: ## 데이터베이스 백업 (backups/ 폴더에 저장)
	@mkdir -p backups
	@$(COMPOSE) exec -T postgres pg_dump -U macro -d macrodash \
		> "backups/macrodash-$$(date +%Y%m%d-%H%M%S).sql"
	@ls -lh backups | tail -1
	@echo "누적 수급 이력은 외부에서 다시 받을 수 없습니다. 주기적으로 백업하세요."

restore: ## 백업 복원 (make restore F=backups/xxx.sql)
	@test -n "$(F)" || (echo "사용법: make restore F=backups/파일.sql" && exit 1)
	$(COMPOSE) exec -T postgres psql -U macro -d macrodash < $(F)

reset: ## ⚠️ 전체 삭제 후 재기동 (수집 이력까지 사라집니다)
	@printf "정말 모든 데이터를 지울까요? 누적 수급 이력은 복구할 수 없습니다 [y/N] " && read ans && [ "$$ans" = "y" ]
	$(COMPOSE) down -v
	$(COMPOSE) up -d --build
