"use client";

import { useState } from "react";
import { Banner, Button, Card, Loading, Select, SourceBadge } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { apiPost } from "@/lib/api";
import type { AiEngine, AiResponse } from "@/lib/types";

/**
 * 🤖 AI 종합 데이터 분석 &amp; 결론 리포트.
 *
 * 프롬프트에는 대시보드가 수집한 <b>원본 텍스트</b>가 그대로 들어갑니다.
 * 추정치·대용 지표에는 경고 문구가 함께 들어가므로, AI가 실제 지표의 임계치를
 * 추정치에 적용하는 것을 막습니다.
 */
export default function AiReportPage() {
  const engines = useApi<{ engines: AiEngine[]; enabled: boolean }>("/api/ai/engines");
  const reportTypes = useApi<{ types: string[] }>("/api/ai/report-types");
  const snapshot = useApi<{ text: string }>("/api/ai/snapshot-text");

  const [engineId, setEngineId] = useState("auto");
  const [reportType, setReportType] = useState("");
  const [extra, setExtra] = useState("");
  const [result, setResult] = useState<AiResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showData, setShowData] = useState(false);

  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      setResult(
        await apiPost<AiResponse>("/api/ai/report", {
          engineId,
          reportType: reportType || reportTypes.data?.types?.[0],
          extraInstruction: extra,
        }),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "리포트 생성에 실패했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const copyData = async () => {
    if (snapshot.data?.text) {
      await navigator.clipboard.writeText(snapshot.data.text);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">🤖 AI 종합 데이터 분석 &amp; 결론 리포트</h1>
        <p className="mt-1 text-xs text-muted">
          수집된 데이터만 근거로 삼습니다. 데이터에 없는 수치는 생성하지 않도록 지시합니다.
        </p>
      </header>

      {engines.data && !engines.data.enabled && (
        <Banner tone="warn">
          AI 키가 하나도 설정되지 않았습니다. NVIDIA / Cerebras / Cloudflare 중 하나 이상을
          설정하면 이 메뉴가 활성화됩니다.
        </Banner>
      )}

      <Card title="리포트 설정">
        <div className="flex flex-wrap items-end gap-3">
          <Select
            label="AI 엔진"
            value={engineId}
            onChange={setEngineId}
            options={(engines.data?.engines ?? []).map((engine) => ({
              value: engine.id,
              label: `${engine.label}${engine.available ? "" : " (키 없음)"}`,
            }))}
          />
          <Select
            label="리포트 종류"
            value={reportType || (reportTypes.data?.types?.[0] ?? "")}
            onChange={setReportType}
            options={(reportTypes.data?.types ?? []).map((type) => ({
              value: type,
              label: type,
            }))}
          />
        </div>

        <label className="mt-4 block text-xs text-muted">
          추가 지시 (선택)
          <textarea
            value={extra}
            onChange={(event) => setExtra(event.target.value)}
            rows={3}
            placeholder="예: 향후 2주 관점에서 코스피200 선물 포지션에 집중해 주세요."
            className="mt-1 w-full rounded-md border border-border bg-canvas px-3 py-2 text-sm text-body outline-none focus:border-accent"
          />
        </label>

        <div className="mt-4 flex flex-wrap gap-2">
          <Button
            variant="primary"
            onClick={generate}
            disabled={busy || !engines.data?.enabled}
          >
            {busy ? "생성 중… (최대 2분)" : "🚀 리포트 생성"}
          </Button>
          <Button onClick={() => setShowData((value) => !value)}>
            {showData ? "원본 데이터 닫기" : "AI에 전달되는 원본 데이터 보기"}
          </Button>
          <Button onClick={copyData} disabled={!snapshot.data?.text}>
            원본 데이터 복사
          </Button>
        </div>
      </Card>

      {showData && (
        <Card title="📋 수집 데이터 원본 (AI 입력)">
          {snapshot.loading && !snapshot.data && <Loading />}
          <pre className="max-h-[480px] overflow-auto whitespace-pre-wrap rounded border border-border bg-canvas p-3 text-[11px] leading-relaxed text-muted">
            {snapshot.data?.text ?? ""}
          </pre>
        </Card>
      )}

      {error && <Banner tone="danger">{error}</Banner>}

      {result && (
        <Card
          title={`📄 ${result.reportType ?? "AI 리포트"}`}
          subtitle={
            result.status
              ? `${result.provider} · ${result.latencyMs}ms${
                  result.translationInfo ? ` · ${result.translationInfo}` : ""
                }`
              : undefined
          }
          actions={result.model ? <SourceBadge>{result.model}</SourceBadge> : undefined}
        >
          {!result.status ? (
            <Banner tone="danger">
              생성 실패: {result.error}
              {result.failoverPath && (
                <ul className="mt-2 list-disc pl-5 text-xs">
                  {result.failoverPath.map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ul>
              )}
            </Banner>
          ) : (
            <>
              {result.failoverPath && result.failoverPath.length > 1 && (
                <p className="mb-3 text-[11px] text-muted">
                  폴오버 경로: {result.failoverPath.join(" → ")}
                </p>
              )}
              <article className="whitespace-pre-wrap text-sm leading-relaxed text-body">
                {result.response}
              </article>
            </>
          )}
        </Card>
      )}
    </div>
  );
}
