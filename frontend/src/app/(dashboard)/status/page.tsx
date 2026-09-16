"use client";

import { useState } from "react";
import {
  Banner,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Loading,
  Metric,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { useRefreshSignal } from "@/hooks/useRefreshSignal";
import { apiPost } from "@/lib/api";
import { EMPTY, formatAge, formatDateTimeKst, formatNumber } from "@/lib/format";
import type { StatusResponse, VerificationResponse } from "@/lib/types";

const TASK_ICONS: Record<string, string> = { ok: "✅", empty: "⚠️", error: "❌" };

const RUN_STATUS_LABEL: Record<string, string> = {
  ok: "정상 종료",
  partial: "일부 실패",
  fail: "전부 실패",
  running: "진행 중",
  interrupted: "⚠️ 비정상 종료 (프로세스가 사라졌거나 신호가 끊겼습니다)",
  none: "기록 없음",
};

/**
 * 🗄️ 데이터 저장소 상태.
 *
 * 구버전 `collector.py --status`가 터미널에 출력하던 내용을 화면으로 옮겼습니다.
 * 핵심은 "무엇이 왜 실패했는지"와 "있어야 하는데 없는 데이터셋"입니다.
 */
export default function StatusPage() {
  const { data, loading, error, reload } = useApi<StatusResponse>("/api/status", 60_000);
  const [running, setRunning] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const { reloadAll } = useRefreshSignal();

  const runTask = async (taskName: string) => {
    setRunning(taskName);
    setMessage(null);
    try {
      const result = await apiPost<{ ok: boolean; okCount?: number; failCount?: number }>(
        `/api/status/run/${taskName}`,
      );
      setMessage(
        result.ok
          ? `${taskName} 실행 완료 (성공 ${result.okCount ?? 0} · 실패 ${result.failCount ?? 0})`
          : "수집기에 연결하지 못했습니다.",
      );
      // 개별 태스크 실행도 화면 전체를 갱신합니다. 이 태스크가 바꾼 스냅샷을
      // 다른 메뉴도 보고 있을 수 있습니다.
      reloadAll();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "실행에 실패했습니다.");
    } finally {
      setRunning(null);
    }
  };

  if (loading && !data) {
    return <Loading label="저장소 상태를 확인하는 중…" />;
  }
  if (error) {
    return <ErrorState message={error} onRetry={reload} />;
  }

  const lastRun = data?.lastRun;
  const resolvedStatus = data?.lastRunStatus ?? "none";

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">🗄️ 데이터 저장소 상태</h1>
        <p className="mt-1 text-xs text-muted">
          수집 현황·신선도·실패 원인·누적 이력 · 읽기 모드 {data?.readMode}
        </p>
      </header>

      {data && !data.collectorReachable && (
        <Banner tone="warn">
          {data.message ?? "수집기에 연결하지 못했습니다. 아래 정보는 데이터베이스에서 직접 읽은 값입니다."}
        </Banner>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Metric
          label="최근 수집 상태"
          value={RUN_STATUS_LABEL[resolvedStatus] ?? resolvedStatus}
          caption={
            lastRun?.startedAt
              ? `${formatDateTimeKst(lastRun.startedAt)} 시작 · 대상 ${lastRun.groupName ?? EMPTY}`
              : undefined
          }
          tone={resolvedStatus === "interrupted" ? "text-warn" : undefined}
        />
        <Metric
          label="성공 / 실패"
          value={`${lastRun?.okCount ?? 0} / ${lastRun?.failCount ?? 0}`}
          caption={lastRun?.detail ?? undefined}
        />
        <Metric
          label="누적 시계열"
          value={`${formatNumber(data?.timeseriesRows ?? 0, 0)} 행`}
        />
        <Metric
          label="누적 수급 레코드"
          value={`${formatNumber(data?.observationRows ?? 0, 0)} 행`}
          caption={`이력 거래일 ${data?.radarHistoryDates?.length ?? 0}일`}
        />
      </div>

      {data?.keys && (
        <Card title="🔑 외부 API 키 보유 현황" subtitle="키가 없는 소스는 해당 기능만 비활성화됩니다.">
          <div className="flex flex-wrap gap-2">
            {Object.entries(data.keys).map(([name, present]) => (
              <span
                key={name}
                className={`rounded border px-3 py-1 text-xs ${
                  present
                    ? "border-ok/40 bg-ok/10 text-ok"
                    : "border-border bg-surface-hover text-muted"
                }`}
              >
                {name.toUpperCase()} {present ? "설정됨" : "없음"}
              </span>
            ))}
          </div>
        </Card>
      )}

      <Card
        title="🧩 태스크별 최근 결과"
        subtitle="✅ 정상 · ⚠️ 데이터 없음(기존 저장본 유지) · ❌ 오류"
      >
        {message && <p className="mb-3 text-xs text-accent">{message}</p>}
        <Table
          rows={data?.taskSummary ?? []}
          rowKey={(row) => row.task}
          emptyMessage="수집 기록이 없습니다. 수집기를 한 번 실행하세요."
          columns={[
            {
              key: "task",
              header: "태스크",
              render: (row) => (
                <span className="flex items-center gap-2">
                  <span>{TASK_ICONS[row.status] ?? "•"}</span>
                  <span className="text-body">{row.task}</span>
                  {row.speed && <SourceBadge>{row.speed}</SourceBadge>}
                </span>
              ),
            },
            {
              key: "startedAt",
              header: "실행 시각",
              render: (row) => formatDateTimeKst(row.startedAt),
            },
            {
              key: "duration",
              header: "소요",
              align: "right",
              render: (row) =>
                row.durationMs === null ? EMPTY : `${(row.durationMs / 1000).toFixed(1)}s`,
            },
            {
              key: "detail",
              header: "상세",
              render: (row) => (
                <span className={row.status === "ok" ? "text-muted" : "text-warn"}>
                  {row.detail ?? EMPTY}
                </span>
              ),
            },
            {
              key: "action",
              header: "",
              align: "right",
              render: (row) => (
                <Button
                  onClick={() => runTask(row.task)}
                  disabled={running !== null}
                >
                  {running === row.task ? "실행 중…" : "다시 실행"}
                </Button>
              ),
            },
          ]}
        />
      </Card>

      <Card
        title="📦 스냅샷 신선도"
        subtitle="수집 시각이 오래된 저장본은 화면에서도 '오래됨'으로 표시됩니다."
      >
        <Table
          rows={data?.snapshots ?? []}
          rowKey={(row) => row.name}
          emptyMessage="저장된 스냅샷이 없습니다."
          columns={[
            { key: "name", header: "데이터셋", render: (row) => row.name },
            {
              key: "status",
              header: "상태",
              render: (row) => (
                <span
                  className={
                    row.status === "estimated"
                      ? "text-warn"
                      : row.status === "ok"
                        ? "text-ok"
                        : "text-danger"
                  }
                >
                  {row.status === "estimated" ? "추정치" : row.status}
                </span>
              ),
            },
            {
              key: "age",
              header: "수집",
              align: "right",
              render: (row) => (
                <span className={row.stale ? "text-warn" : "text-muted"}>
                  {formatAge(row.ageSeconds ?? null)}
                </span>
              ),
            },
            {
              key: "collectedAt",
              header: "수집 시각",
              render: (row) => formatDateTimeKst(row.collectedAt),
            },
          ]}
        />
      </Card>

      <Card
        title="🕳️ 있어야 하는데 없는 데이터셋"
        subtitle="기대 목록과 대조해 누락을 찾습니다. 존재하는 것만 나열하면 누락을 알아챌 수 없습니다."
      >
        {(data?.missingDatasets ?? []).length === 0 ? (
          <p className="text-sm text-ok">누락된 데이터셋이 없습니다.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {data?.missingDatasets?.map((entry) => (
              <span
                key={entry.name}
                className="rounded border border-warn/40 bg-warn/10 px-2 py-1 text-[11px] text-warn"
                title={entry.name}
              >
                {entry.label}
              </span>
            ))}
          </div>
        )}
      </Card>

      <VerificationPanel />
    </div>
  );
}

const VERDICT_STYLE: Record<string, string> = {
  match: "text-ok",
  mismatch: "text-danger",
  skipped: "text-muted",
  error: "text-warn",
};

const VERDICT_ICON: Record<string, string> = {
  match: "✅",
  mismatch: "❌",
  skipped: "⏭️",
  error: "⚠️",
};

function VerificationPanel() {
  const [result, setResult] = useState<VerificationResponse | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      setResult(await apiPost<VerificationResponse>("/api/verification"));
    } catch (err) {
      setError(err instanceof Error ? err.message : "검증에 실패했습니다.");
    } finally {
      setRunning(false);
    }
  };

  return (
    <Card
      title="🔍 데이터 교차 검증 (KRX · KIS)"
      subtitle="같은 수치를 서로 다른 출처가 같게 말하는지 대조합니다. '확인 못 함'과 '일치'는 절대 섞지 않습니다."
      actions={
        <Button variant="primary" onClick={run} disabled={running}>
          {running ? "검증 중…" : "지금 교차 검증 실행"}
        </Button>
      }
    >
      {error && <ErrorState message={error} />}
      {!result && !error && (
        <EmptyState message="아직 실행하지 않았습니다. 시세 대조는 장 마감 후, 수급 대조는 정규장 중에만 가능합니다." />
      )}

      {result && !result.available && <Banner tone="warn">{result.message}</Banner>}

      {result?.available && (
        <>
          <p className="mb-3 text-sm text-body">
            {result.headline} · 검증 시각 {formatDateTimeKst(result.checkedAt)}
          </p>
          <div className="flex flex-col gap-3">
            {result.results?.map((entry) => (
              <div key={entry.name} className="rounded-lg border border-border bg-canvas p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm text-bright">
                    {VERDICT_ICON[entry.verdict]} {entry.name}
                  </span>
                  <span className={`text-xs font-semibold ${VERDICT_STYLE[entry.verdict]}`}>
                    {entry.label}
                    {entry.diffPct !== null && ` · 차이 ${entry.diffPct.toFixed(3)}%`}
                  </span>
                </div>
                {entry.readings.length > 0 && (
                  <ul className="mt-2 flex flex-col gap-1 text-xs text-muted">
                    {entry.readings.map((reading) => (
                      <li key={reading.source} className="flex justify-between gap-3">
                        <span>{reading.source}</span>
                        <span className="tabular-nums">
                          {reading.ok
                            ? `${formatNumber(reading.value, 2)}${
                                reading.detail ? ` (${reading.detail})` : ""
                              }`
                            : `실패: ${reading.detail ?? EMPTY}`}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
                {entry.note && <p className="mt-2 text-xs text-muted">{entry.note}</p>}
              </div>
            ))}
          </div>
        </>
      )}
    </Card>
  );
}
